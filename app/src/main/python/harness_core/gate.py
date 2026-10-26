"""The one place a tool call can actually happen.

Everything the model proposes passes through here, and the order is the
point:

    known tool? -> arguments valid? -> policy -> approval -> run it

No step can be skipped, because there is no other entry point to a tool
handler. The model's text never grants permission; it only ever
proposes, and `ToolGate` decides.

Three things it carries from the predecessor's runtime, separated out
from the planner so that they can be tested without a model in the loop:

**Risk is declared by the tool, never inferred from what the model
said.** A `ToolSpec` states its own `Risk`, and policy reads that.

**Approval binds to arguments.** When a call needs approval, the gate
records a hash of exactly what was proposed. The approval can only be
spent on that.

**Taint spreads forward.** Once a run has read content written by
someone other than the owner - an email, an SMS, a notification - the
run is tainted for the rest of its life, and the policy stops treating
"the owner asked for this" as settled. The instruction may now be the
stranger's.
"""

from __future__ import annotations

import json

import time
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass, field
from typing import Any

from harness_core.approvals import Approval, ApprovalRefused, ApprovalStore
from harness_core.audit import AuditSink, NullAuditSink
from harness_core.policy import Policy, PolicyDecision
from harness_core.tools import (
    SchemaValidationError,
    ToolContext,
    ToolRegistry,
    ToolResult,
)


class GateStatus(str):
    pass


OK = "ok"
ERROR = "error"
DENIED = "denied"
APPROVAL_REQUIRED = "approval_required"


@dataclass
class RunState:
    """What the gate remembers across one run."""

    run_id: str
    actor_id: str
    direct_user_request: bool = True
    #: Set once and never cleared. Untrusted content cannot be un-read.
    tainted: bool = False
    tainted_by: tuple[str, ...] = field(default_factory=tuple)

    def context(self) -> ToolContext:
        return ToolContext(
            actor_id=self.actor_id,
            chat_id=0,
            run_id=self.run_id,
            direct_user_request=self.direct_user_request,
            tainted=self.tainted,
        )


@dataclass(frozen=True)
class GateOutcome:
    status: str
    summary: str
    result: ToolResult | None = None
    approval: Approval | None = None
    rule_id: str = ""
    error_code: str = ""

    @property
    def ran(self) -> bool:
        return self.result is not None


def describe_action(tool: str, arguments: Mapping[str, Any], *, limit: int = 300) -> str:
    """Spell out the exact action, for a human to read before saying yes.

    An approval is bound to a hash of these arguments, which makes it
    impossible to *substitute* the action - but only if the owner knows
    what they are agreeing to. "Approve send_sms?" is not consent; it is
    a button. The recipient and the message have to be on screen.

    Long values are cut, because an approval dialog that has to be
    scrolled is one that gets approved unread.
    """
    if not arguments:
        return f"{tool} (no arguments)"
    lines = [f"{tool}:"]
    for key in sorted(arguments):
        value = arguments[key]
        if isinstance(value, str):
            shown = value
        else:
            shown = json.dumps(value, default=str, sort_keys=True)
        shown = " ".join(shown.split())
        if len(shown) > limit:
            shown = shown[:limit] + f"… (+{len(shown) - limit} more characters)"
        lines.append(f"  {key}: {shown}")
    return "\n".join(lines)


class ToolGate:
    def __init__(
        self,
        registry: ToolRegistry,
        policy: Policy,
        approvals: ApprovalStore,
        audit: AuditSink | None = None,
    ) -> None:
        self.registry = registry
        self.policy = policy
        self.approvals = approvals
        self.audit = audit or NullAuditSink()

    def call(
        self,
        state: RunState,
        name: str,
        arguments: Mapping[str, Any],
        *,
        approval_id: str | None = None,
    ) -> GateOutcome:
        """Propose a tool call. Runs it only if every gate agrees."""
        tool = self.registry.get(name)
        if tool is None:
            # Models invent tool names. This is an ordinary event, not an
            # exception, and the model is told so it can correct itself.
            return self._refuse(state, name, "unknown_tool", f"there is no tool called {name}")

        try:
            validated = tool.validate(arguments)
        except SchemaValidationError as error:
            return self._refuse(state, name, "invalid_arguments", str(error))

        context = state.context()
        verdict = self.policy.evaluate(context, tool, validated)

        if verdict.decision is PolicyDecision.DENY:
            self.audit.emit(
                "tool_denied",
                {
                    "run_id": state.run_id,
                    "tool": name,
                    "rule": verdict.rule_id,
                    "risk": tool.risk.value,
                },
            )
            return GateOutcome(
                status=DENIED,
                summary=verdict.reason,
                rule_id=verdict.rule_id,
                error_code="policy_denied",
            )

        if verdict.decision is PolicyDecision.REQUIRE_APPROVAL:
            if approval_id is None:
                approval = self.approvals.create(
                    actor_id=state.actor_id,
                    run_id=state.run_id,
                    tool=name,
                    tool_version=tool.version,
                    arguments=validated,
                    reason=verdict.reason + "\n\n" + describe_action(name, validated),
                )
                self.audit.emit(
                    "approval_requested",
                    {
                        "run_id": state.run_id,
                        "tool": name,
                        "rule": verdict.rule_id,
                        "approval_id": approval.approval_id,
                        "after_untrusted_content": state.tainted,
                    },
                )
                return GateOutcome(
                    status=APPROVAL_REQUIRED,
                    summary=verdict.reason,
                    approval=approval,
                    rule_id=verdict.rule_id,
                )

            try:
                # Re-hashed inside the store from the validated arguments,
                # so a stale approval cannot be carried to a new action.
                self.approvals.consume(
                    approval_id,
                    actor_id=state.actor_id,
                    tool=name,
                    tool_version=tool.version,
                    arguments=validated,
                )
            except ApprovalRefused as refusal:
                self.audit.emit(
                    "approval_refused",
                    {
                        "run_id": state.run_id,
                        "tool": name,
                        "approval_id": approval_id,
                        "code": refusal.code,
                    },
                )
                return GateOutcome(
                    status=DENIED,
                    summary=refusal.detail,
                    rule_id=verdict.rule_id,
                    error_code=refusal.code,
                )
            self.audit.emit(
                "approval_consumed",
                {"run_id": state.run_id, "tool": name, "approval_id": approval_id},
            )

        elif approval_id is not None:
            # An approval offered for a call that does not need one. Not
            # fatal, but it is recorded: it means something upstream
            # believes this tool is riskier than it declares itself to be.
            self.audit.emit(
                "approval_unnecessary",
                {"run_id": state.run_id, "tool": name, "approval_id": approval_id},
            )

        began = time.monotonic()
        result = _run_with_timeout(tool, context, validated)
        elapsed_ms = round((time.monotonic() - began) * 1000)

        if tool.returns_untrusted_content and result.status == "ok":
            # Mark before returning, so the very next call in this run is
            # already judged under the stricter rule.
            state.tainted = True
            if name not in state.tainted_by:
                state.tainted_by = state.tainted_by + (name,)

        self.audit.emit(
            "tool_finished",
            {
                "run_id": state.run_id,
                "tool": name,
                "status": result.status,
                "risk": tool.risk.value,
                "rule": verdict.rule_id,
                "ms": elapsed_ms,
                "tainted_run": state.tainted,
                "error_code": result.error_code,
            },
        )
        return GateOutcome(
            status=OK if result.status == "ok" else ERROR,
            summary=result.summary,
            result=result,
            rule_id=verdict.rule_id,
            error_code=result.error_code or "",
        )

    def _refuse(self, state: RunState, name: str, code: str, detail: str) -> GateOutcome:
        self.audit.emit(
            "tool_rejected",
            {"run_id": state.run_id, "tool": name, "code": code},
        )
        return GateOutcome(status=ERROR, summary=detail, error_code=code)


def _run_with_timeout(tool, context: ToolContext, arguments: Mapping[str, Any]) -> ToolResult:
    """Run a handler under its declared timeout.

    The worker thread cannot be killed - Python has no safe way to do
    that - so a timed-out call is abandoned rather than stopped, and the
    executor is left to finish on its own. Saying so plainly here is
    better than implying a guarantee that does not exist.
    """
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="harness-tool")
    future = executor.submit(tool.handler, context, arguments)
    try:
        return future.result(timeout=tool.timeout_seconds)
    except FutureTimeout:
        return ToolResult.error(
            f"{tool.name} did not finish within {tool.timeout_seconds:g}s",
            code="tool_timeout",
            retryable=True,
        )
    except Exception as error:  # noqa: BLE001 - a tool must not kill the run
        return ToolResult.error(
            f"{tool.name} failed: {type(error).__name__}: {error}",
            code="tool_exception",
        )
    finally:
        executor.shutdown(wait=False)
