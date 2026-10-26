"""Letting Kotlin register tools with the Python core.

The architecture says the core is Python and the tools are native
Kotlin. Something has to join them, and the shape of that join matters
more than it looks: it is the seam every capability crosses.

The boundary is deliberately one function and two JSON strings.
Arguments go across as JSON, a result comes back as JSON. Nothing else
is shared - no Python objects handed to Kotlin, no Java types leaking
into the core, no callbacks in either direction. That means the core
can still be tested without an Android device at all, which is most of
why it was worth putting the core in Python.

What does *not* cross the boundary is the declared risk. A native tool
states its risk when it registers, and from then on the risk lives in
the `ToolSpec` on this side. The handler cannot change it, so a tool
cannot decide at call time that it is less dangerous than it said.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any, Protocol

from harness_core.tools import Risk, ToolContext, ToolRegistry, ToolResult, ToolSpec


class NativeHandler(Protocol):
    """What Kotlin implements. One method, strings both ways."""

    def run(self, arguments_json: str) -> str: ...


def register_native_tool(
    registry: ToolRegistry,
    *,
    name: str,
    description: str,
    input_schema: Mapping[str, Any] | str,
    risk: str,
    handler: NativeHandler,
    timeout_seconds: float = 15.0,
    version: str = "1",
    returns_untrusted_content: bool = False,
) -> ToolSpec:
    """Register a tool whose implementation lives on the Kotlin side.

    `risk` arrives as a string because it crossed a language boundary;
    an unknown value raises here rather than being quietly downgraded to
    something permissive.
    """
    schema = json.loads(input_schema) if isinstance(input_schema, str) else dict(input_schema)

    def call(context: ToolContext, arguments: Mapping[str, Any]) -> ToolResult:
        payload = json.dumps(
            {
                "arguments": dict(arguments),
                # Passed for logging and for tools that behave
                # differently unattended. It is information, not
                # authority: the decision was already made before here.
                "run_id": context.run_id,
                "actor_id": context.actor_id,
            },
            default=str,
        )
        try:
            raw = handler.run(payload)
        except Exception as error:  # noqa: BLE001 - a Java exception must not escape
            return ToolResult.error(
                f"{name} failed inside the app: {type(error).__name__}: {error}",
                code="native_tool_failed",
            )
        return _decode(name, raw)

    spec = ToolSpec(
        name=name,
        description=description,
        input_schema=schema,
        risk=Risk(risk),
        handler=call,
        timeout_seconds=timeout_seconds,
        version=version,
        returns_untrusted_content=returns_untrusted_content,
    )
    registry.register(spec)
    return spec


def _decode(name: str, raw: Any) -> ToolResult:
    """Turn whatever came back into a ToolResult, or explain why not.

    A native tool returning nonsense is a bug in this app, not something
    the model did, so it is reported as a plain error rather than
    allowed to raise through the gate.
    """
    if raw is None:
        return ToolResult.error(f"{name} returned nothing", code="native_tool_empty")
    try:
        decoded = json.loads(str(raw))
    except json.JSONDecodeError:
        return ToolResult.error(f"{name} returned malformed JSON", code="native_tool_malformed")
    if not isinstance(decoded, dict):
        return ToolResult.error(f"{name} did not return an object", code="native_tool_malformed")

    status = str(decoded.get("status") or "ok")
    summary = str(decoded.get("summary") or "")
    data = decoded.get("data")
    if status == "ok":
        return ToolResult.ok(summary, data if isinstance(data, Mapping) else {})
    return ToolResult(
        status="error",
        summary=summary or f"{name} failed",
        data=data if isinstance(data, Mapping) else {},
        retryable=bool(decoded.get("retryable")),
        error_code=str(decoded.get("error_code") or "native_tool_error"),
    )
