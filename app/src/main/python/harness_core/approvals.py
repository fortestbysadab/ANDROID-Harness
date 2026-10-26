"""Approvals that survive the app being killed.

This is the one part of the core that is **not** ported from the
predecessor. There the store was a dictionary: when the process restarted
- and on Android a background process is restarted constantly - every
pending approval vanished. The owner would tap "yes" on a prompt and be
told the approval had expired, with no way to tell that from a real
timeout.

So it is a SQLite table instead, in the app's private directory. Three
properties matter, and each is tested:

**An approval authorises one exact action.** It records a hash of the
tool, its version and its arguments, taken when the prompt was shown.
Consuming it requires presenting the same hash. An approval for "send a
message to Ravi saying I am late" cannot be redeemed for a different
message, which is the attack that matters when the proposal may have
been shaped by content a stranger wrote.

**It can be spent once.** Consumption is a conditional UPDATE, so two
racing attempts cannot both win. The predecessor's read-then-pop was
locked in-process; a database write is honest about it.

**It expires.** An approval left unanswered for an hour is not consent.
"""

from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import threading
import time
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

#: Long enough that the owner can finish what they were doing, short
#: enough that a stale prompt is not still live the next morning.
DEFAULT_TTL_SECONDS = 15 * 60


def argument_hash(tool: str, version: str, arguments: Mapping[str, Any]) -> str:
    """Bind an approval to exactly what was shown to the owner.

    Sorted keys and no whitespace, so the same arguments always hash the
    same way regardless of how the model happened to order them.
    """
    encoded = json.dumps(
        {"tool": tool, "version": version, "arguments": arguments},
        separators=(",", ":"),
        sort_keys=True,
        default=str,
    )
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Approval:
    approval_id: str
    actor_id: str
    run_id: str
    tool: str
    tool_version: str
    argument_hash: str
    reason: str
    expires_at: float


class ApprovalRefused(Exception):
    """Raised with a named code, so callers never guess at the cause."""

    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


class ApprovalStore:
    """Durable, single-use, action-bound approvals."""

    def __init__(self, database_path: str, ttl_seconds: int = DEFAULT_TTL_SECONDS) -> None:
        self.ttl_seconds = int(ttl_seconds)
        # Chaquopy runs this on whichever thread the app uses, and the
        # scheduler will add more, so the connection is shared under a
        # lock rather than pinned to one thread.
        self._connection = sqlite3.connect(database_path, check_same_thread=False)
        self._lock = threading.Lock()
        self._create_schema()

    def _create_schema(self) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                """
                create table if not exists approvals (
                    approval_id   text primary key,
                    actor_id      text not null,
                    run_id        text not null,
                    tool          text not null,
                    tool_version  text not null,
                    argument_hash text not null,
                    reason        text not null,
                    created_at    real not null,
                    expires_at    real not null,
                    consumed_at   real
                )
                """
            )
            self._connection.execute(
                "create index if not exists approvals_by_run on approvals (run_id)"
            )

    def create(
        self,
        *,
        actor_id: str,
        run_id: str,
        tool: str,
        tool_version: str,
        arguments: Mapping[str, Any],
        reason: str,
    ) -> Approval:
        """Record a pending approval and return it, ready to show."""
        now = time.time()
        approval = Approval(
            approval_id=secrets.token_urlsafe(12),
            actor_id=str(actor_id),
            run_id=str(run_id),
            tool=tool,
            tool_version=tool_version,
            argument_hash=argument_hash(tool, tool_version, arguments),
            reason=reason,
            expires_at=now + self.ttl_seconds,
        )
        with self._lock, self._connection:
            self._connection.execute(
                "insert into approvals values (?, ?, ?, ?, ?, ?, ?, ?, ?, null)",
                (
                    approval.approval_id,
                    approval.actor_id,
                    approval.run_id,
                    approval.tool,
                    approval.tool_version,
                    approval.argument_hash,
                    approval.reason,
                    now,
                    approval.expires_at,
                ),
            )
        return approval

    def consume(
        self,
        approval_id: str,
        *,
        actor_id: str,
        tool: str,
        tool_version: str,
        arguments: Mapping[str, Any],
    ) -> Approval:
        """Spend an approval, or explain precisely why it cannot be spent.

        The arguments are re-hashed here rather than trusted from the
        caller, so an approval cannot be carried over to a different
        action by passing the old hash along with new arguments.
        """
        presented = argument_hash(tool, tool_version, arguments)
        now = time.time()

        with self._lock, self._connection:
            # One conditional write decides it. Two racing callers cannot
            # both see rowcount 1, so an approval cannot be spent twice.
            cursor = self._connection.execute(
                """
                update approvals set consumed_at = ?
                 where approval_id = ?
                   and actor_id = ?
                   and argument_hash = ?
                   and expires_at > ?
                   and consumed_at is null
                """,
                (now, approval_id, str(actor_id), presented, now),
            )
            if cursor.rowcount == 1:
                row = self._connection.execute(
                    "select approval_id, actor_id, run_id, tool, tool_version,"
                    " argument_hash, reason, expires_at"
                    "  from approvals where approval_id = ?",
                    (approval_id,),
                ).fetchone()
                return Approval(*row)

            # It failed. Work out which of the reasons applies, because
            # "expired" and "that is not what you approved" need very
            # different responses from the owner.
            row = self._connection.execute(
                "select actor_id, argument_hash, expires_at, consumed_at"
                "  from approvals where approval_id = ?",
                (approval_id,),
            ).fetchone()

        if row is None:
            raise ApprovalRefused("approval_unknown", "no such approval")
        stored_actor, stored_hash, expires_at, consumed_at = row
        if consumed_at is not None:
            raise ApprovalRefused("approval_already_used", "this approval was already used")
        if stored_actor != str(actor_id):
            raise ApprovalRefused("approval_wrong_actor", "approval belongs to someone else")
        if stored_hash != presented:
            raise ApprovalRefused(
                "approval_action_changed",
                "the action is not the one that was approved",
            )
        if expires_at <= now:
            raise ApprovalRefused("approval_expired", "the approval has expired")
        raise ApprovalRefused("approval_refused", "the approval could not be used")

    def pending(self, actor_id: str) -> list[Approval]:
        """Everything still answerable, newest last."""
        now = time.time()
        with self._lock:
            rows = self._connection.execute(
                "select approval_id, actor_id, run_id, tool, tool_version,"
                " argument_hash, reason, expires_at"
                "  from approvals"
                " where actor_id = ? and consumed_at is null and expires_at > ?"
                " order by created_at",
                (str(actor_id), now),
            ).fetchall()
        return [Approval(*row) for row in rows]

    def purge_expired(self, *, older_than_seconds: int = 24 * 3600) -> int:
        """Housekeeping. Spent and expired rows are kept briefly so that
        "already used" can still be reported, then deleted."""
        cutoff = time.time() - older_than_seconds
        with self._lock, self._connection:
            cursor = self._connection.execute(
                "delete from approvals where expires_at < ?", (cutoff,)
            )
        return cursor.rowcount

    def close(self) -> None:
        with self._lock:
            self._connection.close()
