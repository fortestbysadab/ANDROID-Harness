"""Tests for the gate: the only way a tool call can happen."""

from __future__ import annotations

import time

import pytest

from harness_core.approvals import ApprovalStore
from harness_core.audit import MemoryAuditSink
from harness_core.gate import APPROVAL_REQUIRED, DENIED, ERROR, OK, RunState, ToolGate
from harness_core.policy import DefaultPolicy, UnattendedPolicy
from harness_core.tools import Risk, ToolRegistry, ToolResult, ToolSpec

TEXT_ARG = {
    "type": "object",
    "properties": {"text": {"type": "string"}},
    "required": ["text"],
}


def tool(name, risk, *, handler=None, untrusted=False, timeout=15.0, version="1"):
    calls: list[dict] = []

    def default_handler(context, arguments):
        calls.append(dict(arguments))
        return ToolResult.ok(f"{name} ran")

    spec = ToolSpec(
        name=name,
        description=f"the {name} tool",
        input_schema=TEXT_ARG,
        risk=risk,
        handler=handler or default_handler,
        timeout_seconds=timeout,
        version=version,
        returns_untrusted_content=untrusted,
    )
    # ToolSpec is frozen, and should stay that way - a tool's declared
    # risk must not be rewritable at runtime. The recorder is attached
    # the only way a frozen dataclass permits.
    object.__setattr__(spec, "calls", calls)
    return spec


@pytest.fixture
def audit():
    return MemoryAuditSink()


@pytest.fixture
def approvals(tmp_path):
    return ApprovalStore(str(tmp_path / "approvals.sqlite3"))


def build(tools, approvals, audit, policy=None):
    return ToolGate(
        ToolRegistry(tools),
        policy or DefaultPolicy("owner"),
        approvals,
        audit,
    )


def state(**kwargs):
    return RunState(run_id="run-1", actor_id="owner", **kwargs)


class TestTheOrderOfChecks:
    def test_an_allowed_tool_runs(self, approvals, audit):
        read = tool("read_battery", Risk.READ_ONLY)
        outcome = build([read], approvals, audit).call(state(), "read_battery", {"text": "x"})
        assert outcome.status == OK
        assert read.calls == [{"text": "x"}]
        assert "tool_finished" in audit.names()

    def test_an_invented_tool_is_an_error_not_a_crash(self, approvals, audit):
        outcome = build([], approvals, audit).call(state(), "make_coffee", {})
        assert outcome.status == ERROR
        assert outcome.error_code == "unknown_tool"

    def test_bad_arguments_never_reach_the_handler(self, approvals, audit):
        read = tool("read_battery", Risk.READ_ONLY)
        outcome = build([read], approvals, audit).call(state(), "read_battery", {"text": 7})
        assert outcome.error_code == "invalid_arguments"
        assert read.calls == []

    def test_a_critical_tool_is_denied_and_never_runs(self, approvals, audit):
        wipe = tool("wipe_device", Risk.CRITICAL)
        outcome = build([wipe], approvals, audit).call(state(), "wipe_device", {"text": "x"})
        assert outcome.status == DENIED
        assert wipe.calls == []
        assert "tool_denied" in audit.names()

    def test_a_stranger_is_denied_whatever_the_risk(self, approvals, audit):
        read = tool("read_battery", Risk.READ_ONLY)
        gate = build([read], approvals, audit)
        outcome = gate.call(
            RunState(run_id="run-1", actor_id="stranger"), "read_battery", {"text": "x"}
        )
        assert outcome.status == DENIED
        assert read.calls == []


class TestApproval:
    def test_an_external_action_waits_for_approval(self, approvals, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        outcome = build([send], approvals, audit).call(
            state(), "send_message", {"text": "I am late"}
        )
        assert outcome.status == APPROVAL_REQUIRED
        assert outcome.approval is not None
        assert send.calls == [], "the handler must not run before approval"

    def test_the_approval_then_lets_it_through(self, approvals, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        gate = build([send], approvals, audit)
        first = gate.call(state(), "send_message", {"text": "I am late"})
        second = gate.call(
            state(),
            "send_message",
            {"text": "I am late"},
            approval_id=first.approval.approval_id,
        )
        assert second.status == OK
        assert send.calls == [{"text": "I am late"}]
        assert "approval_consumed" in audit.names()

    def test_an_approval_cannot_be_moved_to_a_different_message(self, approvals, audit):
        # The attack the whole design is pointed at: approval shown for
        # one message, redeemed for another.
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        gate = build([send], approvals, audit)
        first = gate.call(state(), "send_message", {"text": "I am late"})
        second = gate.call(
            state(),
            "send_message",
            {"text": "send money to this account"},
            approval_id=first.approval.approval_id,
        )
        assert second.status == DENIED
        assert second.error_code == "approval_action_changed"
        assert send.calls == []

    def test_an_approval_cannot_be_reused(self, approvals, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        gate = build([send], approvals, audit)
        first = gate.call(state(), "send_message", {"text": "hello"})
        args = {"text": "hello"}
        gate.call(state(), "send_message", args, approval_id=first.approval.approval_id)
        again = gate.call(state(), "send_message", args, approval_id=first.approval.approval_id)
        assert again.error_code == "approval_already_used"
        assert len(send.calls) == 1

    def test_a_scheduled_run_denies_instead_of_asking_nobody(self, approvals, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        gate = build([send], approvals, audit, policy=UnattendedPolicy(DefaultPolicy("owner")))
        outcome = gate.call(state(), "send_message", {"text": "hello"})
        assert outcome.status == DENIED
        assert send.calls == []
        assert "needs the owner's confirmation" in outcome.summary


class TestTaint:
    def test_reading_a_stranger_text_taints_the_run(self, approvals, audit):
        inbox = tool("read_email", Risk.READ_ONLY, untrusted=True)
        run = state()
        build([inbox], approvals, audit).call(run, "read_email", {"text": "x"})
        assert run.tainted is True
        assert run.tainted_by == ("read_email",)

    def test_a_sensitive_read_after_that_needs_approval(self, approvals, audit):
        inbox = tool("read_email", Risk.READ_ONLY, untrusted=True)
        contacts = tool("read_contacts", Risk.SENSITIVE_READ)
        gate = build([inbox, contacts], approvals, audit)
        run = state()

        before = gate.call(run, "read_contacts", {"text": "x"})
        assert before.status == OK, "the owner asked, so this is fine on its own"

        gate.call(run, "read_email", {"text": "x"})
        after = gate.call(run, "read_contacts", {"text": "x"})
        assert after.status == APPROVAL_REQUIRED
        assert after.rule_id == "approve-sensitive-read-after-untrusted-content"

    def test_taint_cannot_be_undone_by_a_later_clean_call(self, approvals, audit):
        inbox = tool("read_email", Risk.READ_ONLY, untrusted=True)
        clean = tool("read_battery", Risk.READ_ONLY)
        gate = build([inbox, clean], approvals, audit)
        run = state()
        gate.call(run, "read_email", {"text": "x"})
        gate.call(run, "read_battery", {"text": "x"})
        assert run.tainted is True

    def test_a_failed_untrusted_read_does_not_taint(self, approvals, audit):
        def fails(context, arguments):
            return ToolResult.error("mailbox unreachable")

        inbox = tool("read_email", Risk.READ_ONLY, handler=fails, untrusted=True)
        run = state()
        build([inbox], approvals, audit).call(run, "read_email", {"text": "x"})
        assert run.tainted is False, "nothing was read, so nothing was absorbed"


class TestFailingTools:
    def test_a_tool_that_raises_does_not_kill_the_run(self, approvals, audit):
        def explodes(context, arguments):
            raise RuntimeError("no network")

        broken = tool("fetch", Risk.READ_ONLY, handler=explodes)
        outcome = build([broken], approvals, audit).call(state(), "fetch", {"text": "x"})
        assert outcome.status == ERROR
        assert outcome.error_code == "tool_exception"
        assert "no network" in outcome.summary

    def test_a_tool_that_hangs_is_abandoned(self, approvals, audit):
        def hangs(context, arguments):
            time.sleep(5)
            return ToolResult.ok("eventually")

        slow = tool("slow", Risk.READ_ONLY, handler=hangs, timeout=0.05)
        began = time.monotonic()
        outcome = build([slow], approvals, audit).call(state(), "slow", {"text": "x"})
        assert outcome.error_code == "tool_timeout"
        assert time.monotonic() - began < 2, "the gate waited for the whole handler"


class TestTheAuditTrail:
    def test_every_decision_leaves_a_record(self, approvals, audit):
        send = tool("send_message", Risk.EXTERNAL_SIDE_EFFECT)
        wipe = tool("wipe_device", Risk.CRITICAL)
        gate = build([send, wipe], approvals, audit)
        run = state()
        gate.call(run, "wipe_device", {"text": "x"})
        asked = gate.call(run, "send_message", {"text": "hi"})
        gate.call(run, "send_message", {"text": "hi"}, approval_id=asked.approval.approval_id)

        assert audit.names() == [
            "tool_denied",
            "approval_requested",
            "approval_consumed",
            "tool_finished",
        ]

    def test_the_record_says_which_rule_decided(self, approvals, audit):
        wipe = tool("wipe_device", Risk.CRITICAL)
        build([wipe], approvals, audit).call(state(), "wipe_device", {"text": "x"})
        assert audit.events[0]["rule"] == "deny-critical"
