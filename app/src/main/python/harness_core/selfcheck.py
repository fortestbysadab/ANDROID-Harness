"""An end-to-end exercise of the core, runnable from the phone.

The unit tests prove the store's behaviour on a build machine. This
proves it on the device, against the real filesystem, through Chaquopy -
which is where the surprises live.
"""

from __future__ import annotations

import os

from harness_core.approvals import ApprovalRefused, ApprovalStore

SEND = {"to": "Ravi", "body": "I am running late"}


def _spend(store: ApprovalStore, approval_id: str, arguments) -> str:
    try:
        store.consume(
            approval_id,
            actor_id="owner",
            tool="send_message",
            tool_version="1",
            arguments=arguments,
        )
        return "allowed"
    except ApprovalRefused as refusal:
        return refusal.code


def approvals(directory: str) -> str:
    """Walk an approval through its whole life and report each step."""
    path = os.path.join(directory, "selfcheck-approvals.sqlite3")
    if os.path.exists(path):
        os.remove(path)

    lines = []
    store = ApprovalStore(path)
    approval = store.create(
        actor_id="owner",
        run_id="selfcheck",
        tool="send_message",
        tool_version="1",
        arguments=SEND,
        reason="sending a message to Ravi",
    )
    lines.append(f"created:            {approval.approval_id}")
    lines.append(f"bound to:           {approval.argument_hash[:16]}…")

    # A different message must not be redeemable against this approval.
    lines.append(
        "changed action:     "
        + _spend(store, approval.approval_id, {**SEND, "to": "Anil"})
    )
    # Closing and reopening stands in for Android killing the process.
    store.close()
    store = ApprovalStore(path)
    lines.append("after restart:      " + _spend(store, approval.approval_id, SEND))
    lines.append("second attempt:     " + _spend(store, approval.approval_id, SEND))
    store.close()
    os.remove(path)

    expected = ["approval_action_changed", "allowed", "approval_already_used"]
    actual = [line.split()[-1] for line in lines[2:]]
    lines.append("")
    lines.append(
        "verdict: APPROVALS HOLD" if actual == expected else f"verdict: WRONG - {actual}"
    )
    return "\n".join(lines)


def ask_the_model(base_url: str, model: str, api_key: str | None = None) -> str:
    """One real round trip to the configured provider.

    Deliberately end to end rather than a ping: listing models proves
    the URL and key, but only a completion proves the *model name* is
    one this endpoint will actually serve.
    """
    from harness_core.llm import OpenAICompatiblePlanner, PlannerError

    planner = OpenAICompatiblePlanner(
        base_url=base_url,
        model=model,
        api_key=api_key,
        timeout_seconds=45.0,
    )
    try:
        response = planner.complete(
            [{"role": "user", "content": "Reply with exactly: ok"}],
            [],
        )
    except PlannerError as error:
        remedy = getattr(error, "remedy", "")
        return f"failed: {error}\n\n{remedy}".strip()

    reported = (response.metadata or {}).get("model", model)
    return (
        f"endpoint: {planner.base_url}\n"
        f"dialect:  {planner.dialect.value}\n"
        f"model:    {reported}\n"
        f"reply:    {(response.text or '').strip()[:200]}\n\n"
        "verdict: MODEL REACHABLE"
    )
