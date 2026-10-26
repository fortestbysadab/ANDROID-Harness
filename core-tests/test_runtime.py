"""Tests for the run loop.

The planner is a scripted stand-in throughout. The point of these tests
is the loop's behaviour - budgets, pausing, and the fact that it cannot
reach a tool except through the gate - not the model's.
"""

from __future__ import annotations

import pytest

from harness_core.approvals import ApprovalStore
from harness_core.audit import MemoryAuditSink
from harness_core.gate import ToolGate
from harness_core.planner import PlannerResponse, ToolCall
from harness_core.policy import DefaultPolicy
from harness_core.runtime import AgentRuntime, RuntimeLimits, RunStatus
from harness_core.tools import Risk, ToolRegistry, ToolResult, ToolSpec

SCHEMA = {
    "type": "object",
    "properties": {"text": {"type": "string"}},
    "required": ["text"],
}


class ScriptedPlanner:
    """Replays a fixed list of responses, then answers in plain text."""

    def __init__(self, *responses: PlannerResponse) -> None:
        self.responses = list(responses)
        self.calls = 0
        self.last_tools = None

    def complete(self, messages, tools):
        self.calls += 1
        self.last_tools = tools
        if self.responses:
            return self.responses.pop(0)
        return PlannerResponse(text="all done")


class BrokenPlanner:
    def complete(self, messages, tools):
        raise ConnectionError("no network")


def tool(name, risk=Risk.READ_ONLY, handler=None, untrusted=False):
    ran: list[dict] = []

    def default(context, arguments):
        ran.append(dict(arguments))
        return ToolResult.ok(f"{name} ran")

    spec = ToolSpec(
        name=name,
        description=f"the {name} tool",
        input_schema=SCHEMA,
        risk=risk,
        handler=handler or default,
        returns_untrusted_content=untrusted,
    )
    object.__setattr__(spec, "ran", ran)
    return spec


def says(name, text="x", call_id="call-1"):
    return PlannerResponse(tool_calls=(ToolCall(call_id, name, {"text": text}),))


@pytest.fixture
def audit():
    return MemoryAuditSink()


def build(tools, planner, tmp_path, audit, limits=None):
    gate = ToolGate(
        ToolRegistry(tools),
        DefaultPolicy("owner"),
        ApprovalStore(str(tmp_path / "approvals.sqlite3")),
        audit,
    )
    return AgentRuntime(
        planner=planner,
        gate=gate,
        system_prompt="You are a careful assistant.",
        limits=limits,
        audit=audit,
    )


def go(runtime, text="do the thing"):
    return runtime.run(text, actor_id="owner", conversation_id="chat-1")


class TestTheHappyPath:
    def test_plain_text_finishes_the_run(self, tmp_path, audit):
        runtime = build([], ScriptedPlanner(PlannerResponse(text="hello")), tmp_path, audit)
        outcome = go(runtime)
        assert outcome.status is RunStatus.COMPLETED
        assert outcome.text == "hello"

    def test_a_tool_call_runs_then_the_model_answers(self, tmp_path, audit):
        battery = tool("read_battery")
        planner = ScriptedPlanner(says("read_battery"), PlannerResponse(text="87 percent"))
        outcome = go(build([battery], planner, tmp_path, audit))
        assert outcome.status is RunStatus.COMPLETED
        assert battery.ran == [{"text": "x"}]
        assert outcome.text == "87 percent"

    def test_the_tool_result_is_fed_back_to_the_model(self, tmp_path, audit):
        battery = tool("read_battery")
        planner = ScriptedPlanner(says("read_battery"))
        outcome = go(build([battery], planner, tmp_path, audit))
        roles = [message["role"] for message in outcome.messages]
        assert roles == ["system", "user", "assistant", "tool", "assistant"]

    def test_the_model_is_told_the_time(self, tmp_path, audit):
        planner = ScriptedPlanner(PlannerResponse(text="hi"))
        outcome = go(build([], planner, tmp_path, audit))
        system = outcome.messages[0]["content"]
        assert "current date and time" in system, "a model with no clock invents one"

    def test_the_model_is_offered_the_tool_schemas(self, tmp_path, audit):
        planner = ScriptedPlanner(PlannerResponse(text="hi"))
        go(build([tool("read_battery")], planner, tmp_path, audit))
        assert planner.last_tools[0]["function"]["name"] == "read_battery"


class TestTheGateIsNotBypassable:
    def test_a_denied_tool_never_runs(self, tmp_path, audit):
        wipe = tool("wipe_device", Risk.CRITICAL)
        planner = ScriptedPlanner(says("wipe_device"), PlannerResponse(text="I cannot"))
        outcome = go(build([wipe], planner, tmp_path, audit))
        assert wipe.ran == []
        assert outcome.status is RunStatus.COMPLETED
        assert outcome.results[0].error_code == "policy_denied"

    def test_an_invented_tool_is_reported_back_not_crashed_on(self, tmp_path, audit):
        planner = ScriptedPlanner(says("make_coffee"), PlannerResponse(text="sorry"))
        outcome = go(build([], planner, tmp_path, audit))
        assert outcome.results[0].error_code == "unknown_tool"

    def test_reading_untrusted_content_marks_the_run(self, tmp_path, audit):
        inbox = tool("read_email", untrusted=True)
        planner = ScriptedPlanner(says("read_email"), PlannerResponse(text="you have mail"))
        outcome = go(build([inbox], planner, tmp_path, audit))
        assert outcome.tainted is True
        assert outcome.tainted_by == ("read_email",)


class TestApprovalPausesTheRun:
    def test_the_run_stops_and_says_what_it_wants(self, tmp_path, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        planner = ScriptedPlanner(says("send_message", "I am late"))
        outcome = go(build([send], planner, tmp_path, audit))
        assert outcome.status is RunStatus.NEEDS_APPROVAL
        assert len(outcome.pending_approvals) == 1
        assert send.ran == []

    def test_it_does_not_keep_planning_while_it_waits(self, tmp_path, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        planner = ScriptedPlanner(says("send_message"), PlannerResponse(text="sent!"))
        outcome = go(build([send], planner, tmp_path, audit))
        assert planner.calls == 1, "it asked the model again while waiting for the owner"
        assert outcome.text != "sent!"

    def test_approving_it_afterwards_runs_exactly_that_action(self, tmp_path, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        runtime = build([send], ScriptedPlanner(says("send_message", "I am late")), tmp_path, audit)
        paused = go(runtime)
        result = runtime.execute_approved(
            paused.pending_approvals[0], {"text": "I am late"}, actor_id="owner"
        )
        assert result.status == "ok"
        assert send.ran == [{"text": "I am late"}]

    def test_approving_it_cannot_be_turned_into_a_different_action(self, tmp_path, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        runtime = build([send], ScriptedPlanner(says("send_message", "I am late")), tmp_path, audit)
        paused = go(runtime)
        result = runtime.execute_approved(
            paused.pending_approvals[0], {"text": "send money"}, actor_id="owner"
        )
        assert result.error_code == "approval_action_changed"
        assert send.ran == []


class TestBudgets:
    def test_a_looping_model_is_stopped(self, tmp_path, audit):
        battery = tool("read_battery")
        # Never answers in text; always calls another tool.
        planner = ScriptedPlanner(*[says("read_battery", f"n{i}", f"c{i}") for i in range(20)])
        runtime = build(
            [battery], planner, tmp_path, audit, limits=RuntimeLimits(max_model_turns=3)
        )
        outcome = go(runtime)
        assert outcome.status is RunStatus.BUDGET_EXHAUSTED
        assert planner.calls == 3

    def test_the_total_number_of_tool_calls_is_capped(self, tmp_path, audit):
        battery = tool("read_battery")
        many = PlannerResponse(
            tool_calls=tuple(ToolCall(f"c{i}", "read_battery", {"text": f"n{i}"}) for i in range(9))
        )
        runtime = build(
            [battery], ScriptedPlanner(many), tmp_path, audit, limits=RuntimeLimits(max_tool_calls=4)
        )
        outcome = go(runtime)
        assert outcome.status is RunStatus.BUDGET_EXHAUSTED
        assert len(battery.ran) == 4

    def test_the_same_call_repeated_is_refused_not_rerun(self, tmp_path, audit):
        battery = tool("read_battery")
        repeats = PlannerResponse(
            tool_calls=tuple(ToolCall(f"c{i}", "read_battery", {"text": "same"}) for i in range(5))
        )
        runtime = build(
            [battery], ScriptedPlanner(repeats), tmp_path, audit, limits=RuntimeLimits(max_same_call=2)
        )
        outcome = go(runtime)
        assert len(battery.ran) == 2
        codes = [result.error_code for result in outcome.results]
        assert codes.count("repeated_call") == 3

    def test_a_repeat_is_still_answered_so_the_model_can_correct_itself(self, tmp_path, audit):
        # Every tool call must get a reply, or the next provider request
        # is structurally invalid.
        battery = tool("read_battery")
        repeats = PlannerResponse(
            tool_calls=tuple(ToolCall(f"c{i}", "read_battery", {"text": "same"}) for i in range(4))
        )
        runtime = build(
            [battery], ScriptedPlanner(repeats), tmp_path, audit, limits=RuntimeLimits(max_same_call=1)
        )
        outcome = go(runtime)
        replies = [m for m in outcome.messages if m["role"] == "tool"]
        assert len(replies) == 4


class TestWhenThingsBreak:
    def test_an_unreachable_model_fails_politely(self, tmp_path, audit):
        outcome = go(build([], BrokenPlanner(), tmp_path, audit))
        assert outcome.status is RunStatus.FAILED
        assert "ConnectionError" in outcome.error
        assert "try again" in outcome.text

    def test_the_failure_does_not_leak_a_traceback_to_the_user(self, tmp_path, audit):
        outcome = go(build([], BrokenPlanner(), tmp_path, audit))
        assert "Traceback" not in outcome.text
