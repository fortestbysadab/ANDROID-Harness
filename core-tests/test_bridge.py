"""The seam between Kotlin and the core.

Kotlin cannot run here, so these tests stand in for it with objects
that have a `run(str) -> str` method - which is the entire contract. If
these pass, the only thing left to get wrong on the device is the
Kotlin side of that one method.

What is really under test is the refusal to trust the far side: a
handler that throws, returns nothing, returns a string that is not
JSON, or tries to talk its own risk down must all be contained here
rather than reaching the gate.
"""

from __future__ import annotations

import json

import pytest
from harness_core.approvals import ApprovalStore
from harness_core.audit import MemoryAuditSink
from harness_core.bridge import register_native_tool
from harness_core.gate import ToolGate
from harness_core.policy import DefaultPolicy
from harness_core.gate import RunState
from harness_core.tools import Risk, ToolContext, ToolRegistry


class FakeNativeTool:
    """Stands in for a Kotlin object across the boundary."""

    def __init__(self, reply: str | None = '{"status":"ok","summary":"done"}'):
        self.reply = reply
        self.payloads: list[str] = []

    def run(self, payload: str) -> str | None:
        self.payloads.append(payload)
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply


SCHEMA = {
    "type": "object",
    "properties": {"city": {"type": "string"}},
    "required": ["city"],
}


def register(registry: ToolRegistry, handler, **overrides):
    options = {
        "name": "check_weather",
        "description": "Look up the weather.",
        "input_schema": SCHEMA,
        "risk": "read_only",
        "handler": handler,
    }
    options.update(overrides)
    return register_native_tool(registry, **options)


def context() -> ToolContext:
    return ToolContext(actor_id="owner", chat_id=0, run_id="run-1")


class TestRegistering:
    def test_the_tool_becomes_callable_through_the_registry(self):
        registry = ToolRegistry()
        register(registry, FakeNativeTool())
        assert [tool.name for tool in registry] == ["check_weather"]

    def test_a_schema_may_arrive_as_a_json_string(self):
        # Kotlin holds its schema as a string literal; parsing it here
        # saves every tool author writing the same conversion.
        registry = ToolRegistry()
        spec = register(registry, FakeNativeTool(), input_schema=json.dumps(SCHEMA))
        assert spec.input_schema == SCHEMA

    def test_an_unknown_risk_is_refused_at_registration(self):
        # Not quietly mapped to something permissive: a typo in a risk
        # name must fail at startup, loudly, not at the moment the tool
        # does something irreversible.
        with pytest.raises(ValueError):
            register(ToolRegistry(), FakeNativeTool(), risk="mostly_harmless")

    def test_the_declared_risk_is_what_the_spec_carries(self):
        spec = register(
            ToolRegistry(), FakeNativeTool(), risk="external_side_effect"
        )
        assert spec.risk is Risk.EXTERNAL_SIDE_EFFECT

    def test_untrusted_content_is_declared_at_registration(self):
        spec = register(
            ToolRegistry(), FakeNativeTool(), returns_untrusted_content=True
        )
        assert spec.returns_untrusted_content is True


class TestCrossingTheBoundary:
    def test_arguments_arrive_as_json(self):
        handler = FakeNativeTool()
        spec = register(ToolRegistry(), handler)
        spec.handler(context(), {"city": "Asansol"})
        assert json.loads(handler.payloads[0])["arguments"] == {"city": "Asansol"}

    def test_the_run_and_actor_travel_with_the_call(self):
        handler = FakeNativeTool()
        spec = register(ToolRegistry(), handler)
        spec.handler(context(), {"city": "Asansol"})
        payload = json.loads(handler.payloads[0])
        assert payload["run_id"] == "run-1"
        assert payload["actor_id"] == "owner"

    def test_a_successful_reply_becomes_a_tool_result(self):
        handler = FakeNativeTool(
            json.dumps({"status": "ok", "summary": "22C", "data": {"celsius": 22}})
        )
        spec = register(ToolRegistry(), handler)
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.status == "ok"
        assert result.summary == "22C"
        assert result.data == {"celsius": 22}

    def test_an_error_reply_keeps_its_code_and_retryability(self):
        handler = FakeNativeTool(
            json.dumps(
                {
                    "status": "error",
                    "summary": "no network",
                    "error_code": "offline",
                    "retryable": True,
                }
            )
        )
        spec = register(ToolRegistry(), handler)
        result = spec.handler(context(), {"city": "Asansol"})
        assert (result.status, result.error_code, result.retryable) == (
            "error",
            "offline",
            True,
        )

    def test_unserialisable_arguments_do_not_crash_the_call(self):
        # The gate validated the arguments, but a schema permitting
        # arbitrary values could still yield something json cannot
        # encode. str() is the right answer: better a odd-looking
        # argument than a dead run.
        handler = FakeNativeTool()
        spec = register(
            ToolRegistry(),
            handler,
            input_schema={"type": "object", "properties": {}},
        )
        spec.handler(context(), {"when": object()})
        assert "object object" in handler.payloads[0]


class TestWhenTheNativeSideMisbehaves:
    """A bug in Kotlin is this app's fault; it must not look like a tool
    result the model should reason about, nor take the process down."""

    def test_a_thrown_exception_becomes_an_error_result(self):
        spec = register(ToolRegistry(), FakeNativeTool(RuntimeError("npe")))
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.status == "error"
        assert result.error_code == "native_tool_failed"
        assert "npe" in result.summary

    def test_nothing_returned_is_reported_as_empty(self):
        spec = register(ToolRegistry(), FakeNativeTool(None))
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.error_code == "native_tool_empty"

    def test_a_non_json_reply_is_reported_as_malformed(self):
        spec = register(ToolRegistry(), FakeNativeTool("Exception in thread main"))
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.error_code == "native_tool_malformed"

    def test_a_json_array_is_not_a_result(self):
        spec = register(ToolRegistry(), FakeNativeTool("[1, 2, 3]"))
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.error_code == "native_tool_malformed"

    def test_an_error_without_a_code_still_gets_one(self):
        spec = register(ToolRegistry(), FakeNativeTool('{"status":"error"}'))
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.error_code == "native_tool_error"

    def test_data_that_is_not_an_object_is_dropped_not_passed_on(self):
        spec = register(
            ToolRegistry(), FakeNativeTool('{"status":"ok","summary":"hi","data":"oops"}')
        )
        result = spec.handler(context(), {"city": "Asansol"})
        assert result.data == {}


class TestRiskCannotBeTalkedDown:
    """The property the whole seam exists to protect."""

    def test_a_handler_claiming_a_lower_risk_in_its_reply_is_ignored(self):
        handler = FakeNativeTool(
            json.dumps({"status": "ok", "summary": "sent", "risk": "read_only"})
        )
        spec = register(
            ToolRegistry(),
            handler,
            name="send_message",
            risk="external_side_effect",
        )
        spec.handler(context(), {"city": "x"})
        assert spec.risk is Risk.EXTERNAL_SIDE_EFFECT

    def test_the_gate_asks_for_approval_using_the_declared_risk(self, tmp_path):
        # End to end through the real gate: a native tool registered as
        # having an external side effect stops for approval, and the
        # handler is never reached.
        registry = ToolRegistry()
        handler = FakeNativeTool()
        register(
            registry,
            handler,
            name="send_message",
            risk="external_side_effect",
        )
        gate = ToolGate(
            registry,
            DefaultPolicy("owner"),
            ApprovalStore(str(tmp_path / "a.sqlite3")),
            MemoryAuditSink(),
        )
        outcome = gate.call(
            RunState(run_id="r1", actor_id="owner"),
            "send_message",
            {"city": "Asansol"},
        )
        assert outcome.status == "approval_required"
        assert handler.payloads == []

    def test_a_read_only_native_tool_runs_without_asking(self, tmp_path):
        registry = ToolRegistry()
        handler = FakeNativeTool()
        register(registry, handler)
        gate = ToolGate(
            registry,
            DefaultPolicy("owner"),
            ApprovalStore(str(tmp_path / "a.sqlite3")),
            MemoryAuditSink(),
        )
        outcome = gate.call(
            RunState(run_id="r1", actor_id="owner"),
            "check_weather",
            {"city": "Asansol"},
        )
        assert outcome.status == "ok"
        assert len(handler.payloads) == 1

    def test_the_gate_still_validates_arguments_before_kotlin_sees_them(self, tmp_path):
        # Kotlin must never have to re-check the schema; if this breaks,
        # every native tool silently inherits an input-validation bug.
        registry = ToolRegistry()
        handler = FakeNativeTool()
        register(registry, handler)
        gate = ToolGate(
            registry,
            DefaultPolicy("owner"),
            ApprovalStore(str(tmp_path / "a.sqlite3")),
            MemoryAuditSink(),
        )
        outcome = gate.call(
            RunState(run_id="r1", actor_id="owner"), "check_weather", {}
        )
        assert outcome.status != "ok"
        assert handler.payloads == []


class TestRefusingBeforeAsking:
    """The second bug the owner found on the phone.

    Texting "1-800-FLOWERS" asked for approval, and only after the
    owner approved it did the tool say it was not a phone number. So
    did dialling 112, which Android forbids outright. Both prompts were
    for actions that could never happen.

    That is worse than it sounds. An approval prompt is a request for
    the owner's attention, and spending it on decisions that do not
    matter is how a prompt becomes something to tap past. The checks
    now run before the policy, so an impossible call is refused while
    refusing is still free.
    """

    class CheckingTool(FakeNativeTool):
        def __init__(self, refusal: str | None = None, reply: str = '{"status":"ok","summary":"sent"}'):
            super().__init__(reply)
            self.refusal = refusal
            self.checked: list[str] = []

        def check(self, payload: str):
            self.checked.append(payload)
            return self.refusal

    def gate_for(self, tmp_path, registry):
        from harness_core.gate import RunState

        return (
            ToolGate(
                registry,
                DefaultPolicy("owner"),
                ApprovalStore(str(tmp_path / "a.sqlite3")),
                MemoryAuditSink(),
            ),
            RunState(run_id="r1", actor_id="owner"),
        )

    def register_checking(self, registry, handler):
        return register(
            registry,
            handler,
            name="send_message",
            risk="external_side_effect",
            checks_arguments=True,
        )

    def test_an_impossible_call_never_becomes_an_approval(self, tmp_path):
        registry = ToolRegistry()
        handler = self.CheckingTool(
            refusal=json.dumps(
                {"status": "error", "summary": "not a phone number", "error_code": "bad_number"}
            )
        )
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)

        outcome = gate.call(state, "send_message", {"city": "1-800-FLOWERS"})
        assert outcome.status == "error"
        assert outcome.error_code == "bad_number"
        assert outcome.approval is None
        assert handler.payloads == []

    def test_the_refusal_explains_itself(self, tmp_path):
        registry = ToolRegistry()
        handler = self.CheckingTool(
            refusal='{"status":"error","summary":"not a phone number","error_code":"bad_number"}'
        )
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)
        assert "not a phone number" in gate.call(state, "send_message", {"city": "x"}).summary

    def test_a_workable_call_still_asks(self, tmp_path):
        # The control: the check must not be quietly swallowing the
        # approval step for everything.
        registry = ToolRegistry()
        handler = self.CheckingTool(refusal=None)
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)

        outcome = gate.call(state, "send_message", {"city": "9876543210"})
        assert outcome.status == "approval_required"
        assert handler.checked, "the check should have run"
        assert handler.payloads == []

    def test_the_check_sees_the_arguments(self, tmp_path):
        registry = ToolRegistry()
        handler = self.CheckingTool()
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)
        gate.call(state, "send_message", {"city": "Asansol"})
        assert json.loads(handler.checked[0])["arguments"] == {"city": "Asansol"}

    def test_a_check_cannot_approve_anything(self, tmp_path):
        # A tool answering "ok" to the check must not thereby skip the
        # approval it would otherwise need. The check can only refuse.
        registry = ToolRegistry()
        handler = self.CheckingTool(refusal='{"status":"ok","summary":"looks fine"}')
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)
        assert gate.call(state, "send_message", {"city": "x"}).status == "approval_required"

    def test_a_broken_check_does_not_disable_the_tool(self, tmp_path):
        # A crash in a convenience feature must not take a working
        # capability down with it.
        registry = ToolRegistry()

        class Exploding(self.CheckingTool):
            def check(self, payload: str):
                raise RuntimeError("npe")

        handler = Exploding()
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)
        assert gate.call(state, "send_message", {"city": "x"}).status == "approval_required"

    def test_a_check_returning_nothing_means_nothing_is_wrong(self, tmp_path):
        registry = ToolRegistry()
        handler = self.CheckingTool(refusal="")
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)
        assert gate.call(state, "send_message", {"city": "x"}).status == "approval_required"

    def test_a_tool_that_does_not_opt_in_is_never_asked(self, tmp_path):
        # The crossing is paid on every proposal, so it is opt-in.
        registry = ToolRegistry()
        handler = self.CheckingTool(refusal='{"status":"error","summary":"no"}')
        register(registry, handler, name="send_message", risk="external_side_effect")
        gate, state = self.gate_for(tmp_path, registry)
        assert gate.call(state, "send_message", {"city": "x"}).status == "approval_required"
        assert handler.checked == []

    def test_bad_arguments_are_still_caught_before_the_check(self, tmp_path):
        # Order matters: the schema runs first, so a check never sees
        # arguments that failed validation.
        registry = ToolRegistry()
        handler = self.CheckingTool()
        self.register_checking(registry, handler)
        gate, state = self.gate_for(tmp_path, registry)
        outcome = gate.call(state, "send_message", {})
        assert outcome.status == "error"
        assert handler.checked == []

    def test_the_bridge_itself_promises_a_check_never_raises(self, tmp_path):
        # Tested through the spec rather than the gate on purpose. The
        # gate has its own guard, so going through it would pass even
        # with this one deleted - the mutation sweep said exactly that.
        # Each layer has to be worth its place on its own: the bridge's
        # job is that nothing from Java escapes into the core, and that
        # is what is checked here.
        registry = ToolRegistry()

        class Exploding(self.CheckingTool):
            def check(self, payload: str):
                raise RuntimeError("a Java exception crossing over")

        spec = self.register_checking(registry, Exploding())
        assert spec.precheck is not None
        assert spec.precheck({"city": "x"}) is None
