"""The run loop: ask the model, gate what it proposes, repeat, stop.

Rewritten rather than ported. The predecessor's runtime did two jobs at
once - it drove the conversation *and* re-implemented the policy and
approval checks inline. That duplication is exactly where an
authorisation bug hides, because there are then two places that decide
whether something may run and only one of them gets updated.

Here the runtime drives the conversation and nothing else. Every
proposal goes through `ToolGate`, which is the single place a handler
can be reached from. The runtime cannot run a tool even if it wanted to.

What it is responsible for:

* **Budgets**, so a confused model cannot loop for ever on someone's
  battery. Three of them: model turns, total tool calls, and repeats of
  the identical call.
* **Pausing for approval.** A run that needs confirmation stops and
  reports what it wants to do. It does not wait, because on a phone
  nothing may answer for hours.
* **Telling the model the time.** Models have no clock, and without one
  they invent an offset - which is how a task asked for at 11:10 PM got
  scheduled for 07:59 the next morning in the predecessor.
"""

from __future__ import annotations

import hashlib
import json
import logging
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any

from harness_core.approvals import Approval
from harness_core.audit import AuditSink, NullAuditSink
from harness_core.gate import APPROVAL_REQUIRED, RunState, ToolGate
from harness_core.planner import Planner, PlannerResponse, ToolCall
from harness_core.tools import ToolResult

logger = logging.getLogger(__name__)


class RunStatus(str, Enum):
    COMPLETED = "completed"
    NEEDS_APPROVAL = "needs_approval"
    BUDGET_EXHAUSTED = "budget_exhausted"
    FAILED = "failed"


@dataclass(frozen=True)
class RuntimeLimits:
    max_model_turns: int = 8
    max_tool_calls: int = 12
    #: A model that calls the same tool with the same arguments a third
    #: time is stuck, not working.
    max_same_call: int = 2


@dataclass(frozen=True)
class RunOutcome:
    run_id: str
    status: RunStatus
    text: str
    results: tuple[ToolResult, ...] = ()
    pending_approvals: tuple[Approval, ...] = ()
    messages: tuple[Mapping[str, Any], ...] = ()
    error: str = ""
    tainted: bool = False
    tainted_by: tuple[str, ...] = field(default_factory=tuple)
    #: The arguments behind each pending approval, keyed by approval id.
    #: The approval itself stores only a hash - deliberately, so that it
    #: authorises one exact action and cannot be re-pointed. But that
    #: means something has to remember what was actually proposed, or
    #: the owner's "yes" has nothing to execute.
    pending_arguments: Mapping[str, Mapping[str, Any]] = field(default_factory=dict)


class AgentRuntime:
    def __init__(
        self,
        *,
        planner: Planner,
        gate: ToolGate,
        system_prompt: str = "",
        limits: RuntimeLimits | None = None,
        audit: AuditSink | None = None,
        clock: Callable[[], str] | None = None,
    ) -> None:
        self.planner = planner
        self.gate = gate
        self.system_prompt = system_prompt.strip()
        self.limits = limits or RuntimeLimits()
        self.audit = audit or NullAuditSink()
        self.clock = clock or _local_time_line

    def run(
        self,
        user_text: str,
        *,
        actor_id: str,
        conversation_id: str,
        prior_messages: Sequence[Mapping[str, Any]] = (),
        tainted_by: Sequence[str] = (),
    ) -> RunOutcome:
        run_id = uuid.uuid4().hex
        # Taint arrives with the conversation, not just with this turn.
        # The untrusted text that caused it is in prior_messages, about
        # to be replayed to the model, so the suspicion has to come with
        # it - otherwise a planted instruction simply waits one turn.
        carried = tuple(dict.fromkeys(str(name) for name in tainted_by))
        state = RunState(
            run_id=run_id,
            actor_id=str(actor_id),
            tainted=bool(carried),
            tainted_by=carried,
        )

        messages: list[Mapping[str, Any]] = []
        preamble = "\n\n".join(
            part for part in (self.system_prompt, self.clock()) if part.strip()
        )
        if preamble:
            messages.append({"role": "system", "content": preamble})
        messages.extend(prior_messages)
        messages.append({"role": "user", "content": user_text})

        results: list[ToolResult] = []
        pending: list[Approval] = []
        proposed: dict[str, Mapping[str, Any]] = {}
        seen: dict[str, int] = {}
        total_calls = 0

        self.audit.emit(
            "run_started",
            {"run_id": run_id, "actor_id": str(actor_id), "conversation": conversation_id},
        )

        for turn in range(self.limits.max_model_turns):
            try:
                response = self.planner.complete(messages, self.gate.registry.model_schemas())
            except Exception as error:  # noqa: BLE001 - a bad model must not crash the app
                logger.exception("planner failed on run %s turn %d", run_id, turn + 1)
                self.audit.emit(
                    "run_failed",
                    {"run_id": run_id, "reason": "planner_error", "type": type(error).__name__},
                )
                return RunOutcome(
                    run_id,
                    RunStatus.FAILED,
                    "I could not reach the model just now. Please try again.",
                    tuple(results),
                    messages=tuple(messages),
                    error=f"{type(error).__name__}: {error}"[:500],
                    tainted=state.tainted,
                    tainted_by=state.tainted_by,
                )

            messages.append(_assistant_message(response))

            if not response.tool_calls:
                self.audit.emit("run_completed", {"run_id": run_id, "turns": turn + 1})
                return RunOutcome(
                    run_id,
                    RunStatus.COMPLETED,
                    (response.text or "").strip(),
                    tuple(results),
                    messages=tuple(messages),
                    tainted=state.tainted,
                    tainted_by=state.tainted_by,
                )

            for call in response.tool_calls:
                total_calls += 1
                if total_calls > self.limits.max_tool_calls:
                    return self._exhausted(
                        run_id, results, messages, state, "tool call budget ran out"
                    )

                signature = _signature(call)
                seen[signature] = seen.get(signature, 0) + 1
                if seen[signature] > self.limits.max_same_call:
                    # Answer the call rather than dropping it, so the
                    # model sees why and stops instead of trying again.
                    repeated = ToolResult.error(
                        f"{call.name} was already called with these arguments; "
                        "use the earlier result instead of repeating it.",
                        code="repeated_call",
                    )
                    results.append(repeated)
                    messages.append(_tool_message(call, repeated))
                    continue

                outcome = self.gate.call(state, call.name, call.arguments)

                if outcome.status == APPROVAL_REQUIRED and outcome.approval is not None:
                    pending.append(outcome.approval)
                    proposed[outcome.approval.approval_id] = dict(call.arguments)
                    waiting = ToolResult(
                        status="awaiting_approval",
                        summary=outcome.summary,
                        error_code="approval_required",
                    )
                    results.append(waiting)
                    messages.append(_tool_message(call, waiting))
                    continue

                result = outcome.result or ToolResult(
                    status="denied" if outcome.status == "denied" else "error",
                    summary=outcome.summary,
                    error_code=outcome.error_code or None,
                )
                results.append(result)
                messages.append(_tool_message(call, result))

            if pending:
                # Stop here. Nothing later in this run can be planned
                # sensibly until the owner has answered, and on a phone
                # that may be hours away.
                self.audit.emit(
                    "run_paused",
                    {"run_id": run_id, "awaiting": [item.approval_id for item in pending]},
                )
                return RunOutcome(
                    run_id,
                    RunStatus.NEEDS_APPROVAL,
                    _approval_text(pending),
                    tuple(results),
                    tuple(pending),
                    messages=tuple(messages),
                    tainted=state.tainted,
                    tainted_by=state.tainted_by,
                    pending_arguments=proposed,
                )

        return self._exhausted(run_id, results, messages, state, "model turn budget ran out")

    def execute_approved(
        self,
        approval: Approval,
        arguments: Mapping[str, Any],
        *,
        actor_id: str,
    ) -> ToolResult:
        """Run the one action the owner just approved.

        The arguments are passed in and re-hashed by the store, so this
        cannot be used to run anything other than what was shown.
        """
        state = RunState(run_id=approval.run_id, actor_id=str(actor_id))
        outcome = self.gate.call(
            state,
            approval.tool,
            arguments,
            approval_id=approval.approval_id,
        )
        return outcome.result or ToolResult(
            status="denied",
            summary=outcome.summary,
            error_code=outcome.error_code or None,
        )

    def _exhausted(self, run_id, results, messages, state, reason: str) -> RunOutcome:
        self.audit.emit("run_budget_exhausted", {"run_id": run_id, "reason": reason})
        return RunOutcome(
            run_id,
            RunStatus.BUDGET_EXHAUSTED,
            f"I stopped safely because the {reason}.",
            tuple(results),
            messages=tuple(messages),
            tainted=state.tainted,
            tainted_by=state.tainted_by,
        )


def _approval_text(pending: Sequence[Approval]) -> str:
    if len(pending) == 1:
        return f"This needs your approval first: {pending[0].reason}"
    reasons = "; ".join(item.reason for item in pending)
    return f"These need your approval first: {reasons}"


def _assistant_message(response: PlannerResponse) -> dict[str, Any]:
    message: dict[str, Any] = {"role": "assistant", "content": response.text or ""}
    if response.tool_calls:
        message["tool_calls"] = [
            {
                "id": call.id,
                "type": "function",
                "function": {
                    "name": call.name,
                    "arguments": json.dumps(dict(call.arguments), default=str),
                },
                # Replayed verbatim. Gemini thinking models require their
                # own signature back; it is never interpreted and never
                # authorises anything.
                **({"extra_content": dict(call.extra_content)} if call.extra_content else {}),
            }
            for call in response.tool_calls
        ]
    return message


def _tool_message(call: ToolCall, result: ToolResult) -> dict[str, Any]:
    return {
        "role": "tool",
        "tool_call_id": call.id,
        "name": call.name,
        "content": json.dumps(result.as_dict(), separators=(",", ":"), sort_keys=True, default=str),
    }


def _signature(call: ToolCall) -> str:
    encoded = json.dumps(
        {"name": call.name, "arguments": dict(call.arguments)},
        separators=(",", ":"),
        sort_keys=True,
        default=str,
    )
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _local_time_line() -> str:
    """The device's own date and time, stated as a fact.

    Phrased rather than instructed, so it reads naturally wherever the
    model needs it, and included on every run because a model with no
    clock will confidently invent one.
    """
    now = datetime.now().astimezone()
    return f"The current date and time is {now.strftime('%A %d %B %Y, %H:%M %Z')}."
