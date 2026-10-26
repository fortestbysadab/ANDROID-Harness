"""Expiring conversation sessions with durable, structurally valid history.

A session is a short-lived conversation window. It expires after a period of
inactivity so that an old, stale context never silently leaks into a new
request, and so that sensitive tool output (contacts, SMS, location) does not
linger in model context indefinitely.

Storage is SQLite because Android freely kills background processes; an
in-memory store would lose the conversation on every restart.

Sessions are keyed by an opaque conversation id rather than the
predecessor's numeric Telegram chat id. The app itself is now the
primary surface and channels come later, so the key had to stop being
one channel's identifier.

Two structural invariants matter, because violating either makes the provider
reject the next request with HTTP 400:

1. Every assistant message carrying `tool_calls` must be followed by exactly
   one `tool` message per call id. Trimming must never orphan a tool call.
2. History is trimmed only at user-message boundaries, which keeps whole
   request/response cycles intact.
"""

from __future__ import annotations

import json
import logging
import sqlite3
import threading
import time
import uuid
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

DEFAULT_TTL_SECONDS = 15 * 60
DEFAULT_MAX_MESSAGES = 60
DEFAULT_MAX_BYTES = 120_000


def _moment(now: float | None) -> float:
    """The supplied time, or the real one.

    Written out rather than `now or time.time()`, which quietly treats a
    timestamp of zero as "not supplied". That is only reachable from a
    test today, but a conditional that is wrong for one input is wrong,
    and the version that reads slightly longer is the one that cannot
    surprise anybody.
    """
    return time.time() if now is None else now


@dataclass(frozen=True)
class Session:
    session_id: str
    conversation_id: str
    created_at: float
    last_active_at: float
    messages: tuple[Mapping[str, Any], ...]
    turns: int
    #: Tools whose untrusted output is sitting in this history. Taint is a
    #: property of the *conversation*, not of one turn: the email that tried
    #: to issue instructions is replayed to the model on every later turn,
    #: so the suspicion has to last exactly as long as the text does.
    tainted_by: tuple[str, ...] = ()

    @property
    def tainted(self) -> bool:
        return bool(self.tainted_by)

    def age_seconds(self, now: float | None = None) -> float:
        return _moment(now) - self.created_at

    def idle_seconds(self, now: float | None = None) -> float:
        return _moment(now) - self.last_active_at

    def expires_in(self, ttl_seconds: float, now: float | None = None) -> float:
        return max(0.0, ttl_seconds - self.idle_seconds(now))


def close_open_tool_calls(
    messages: Sequence[Mapping[str, Any]], reason: str = "not_executed"
) -> list[dict[str, Any]]:
    """Append a synthetic result for any tool call that never got one.

    An assistant message proposing a tool call with no matching `tool` reply
    is a protocol violation. This happens legitimately when a run pauses for
    approval or is cut short by a budget. Rather than discard the turn, close
    it honestly so the model can see what happened.
    """
    answered: set[str] = {
        str(message.get("tool_call_id"))
        for message in messages
        if message.get("role") == "tool" and message.get("tool_call_id")
    }

    closed: list[dict[str, Any]] = []
    for message in messages:
        closed.append(dict(message))
        if message.get("role") != "assistant":
            continue
        for call in message.get("tool_calls") or []:
            if not isinstance(call, Mapping):
                continue
            call_id = str(call.get("id") or "")
            if not call_id or call_id in answered:
                continue
            answered.add(call_id)
            closed.append(
                {
                    "role": "tool",
                    "tool_call_id": call_id,
                    "name": (call.get("function") or {}).get("name", "unknown"),
                    "content": json.dumps(
                        {
                            "status": reason,
                            "summary": "This action was not executed.",
                            "data": {},
                        },
                        separators=(",", ":"),
                    ),
                }
            )
    return closed


def _blocks(messages: Sequence[Mapping[str, Any]]) -> list[list[Mapping[str, Any]]]:
    """Group messages into user-initiated blocks, a safe place to cut."""
    grouped: list[list[Mapping[str, Any]]] = []
    for message in messages:
        if message.get("role") == "user" or not grouped:
            grouped.append([message])
        else:
            grouped[-1].append(message)
    return grouped


def trim_history(
    messages: Sequence[Mapping[str, Any]],
    *,
    max_messages: int = DEFAULT_MAX_MESSAGES,
    max_bytes: int = DEFAULT_MAX_BYTES,
) -> list[Mapping[str, Any]]:
    """Drop the oldest whole turns until the history fits the budget.

    Cutting only at user boundaries guarantees no tool call is orphaned.
    """
    grouped = _blocks([m for m in messages if m.get("role") != "system"])
    kept: list[list[Mapping[str, Any]]] = []
    total_messages = 0
    total_bytes = 0

    for block in reversed(grouped):
        block_bytes = len(json.dumps(block, default=str))
        if kept and (
            total_messages + len(block) > max_messages
            or total_bytes + block_bytes > max_bytes
        ):
            break
        kept.insert(0, block)
        total_messages += len(block)
        total_bytes += block_bytes

    return [message for block in kept for message in block]


class SqliteSessionStore:
    """Durable, TTL-bounded conversation sessions keyed by chat id."""

    def __init__(
        self,
        path: str | Path,
        *,
        ttl_seconds: float = DEFAULT_TTL_SECONDS,
        max_messages: int = DEFAULT_MAX_MESSAGES,
        max_bytes: int = DEFAULT_MAX_BYTES,
    ) -> None:
        self.path = Path(path).expanduser()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.ttl_seconds = ttl_seconds
        self.max_messages = max_messages
        self.max_bytes = max_bytes
        self._lock = threading.Lock()
        self._connection = sqlite3.connect(str(self.path), check_same_thread=False)
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._connection.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                conversation_id        TEXT    PRIMARY KEY,
                session_id     TEXT    NOT NULL,
                created_at     REAL    NOT NULL,
                last_active_at REAL    NOT NULL,
                turns          INTEGER NOT NULL DEFAULT 0,
                messages       TEXT    NOT NULL,
                tainted_by     TEXT    NOT NULL DEFAULT '[]'
            )
            """
        )
        # An install from before taint was persisted has the old table.
        # Adding the column is cheaper and far less alarming than
        # dropping the owner's conversations to make room for it.
        columns = {
            row[1] for row in self._connection.execute("PRAGMA table_info(sessions)")
        }
        if "tainted_by" not in columns:
            self._connection.execute(
                "ALTER TABLE sessions ADD COLUMN tainted_by TEXT NOT NULL DEFAULT '[]'"
            )
        self._connection.commit()

    # -- reads --------------------------------------------------------

    def active(self, conversation_id: str, now: float | None = None) -> Session | None:
        """Return the live session for a chat, expiring a stale one first."""
        moment = _moment(now)
        with self._lock:
            row = self._connection.execute(
                "SELECT session_id, created_at, last_active_at, turns, messages, tainted_by "
                "FROM sessions WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if row is None:
                return None
            session_id, created_at, last_active_at, turns, payload, taint = row
            if moment - last_active_at >= self.ttl_seconds:
                self._connection.execute(
                    "DELETE FROM sessions WHERE conversation_id = ?", (conversation_id,)
                )
                self._connection.commit()
                logger.info(
                    "Session %s for chat %s expired after %.0fs idle",
                    session_id,
                    conversation_id,
                    moment - last_active_at,
                )
                return None
            try:
                messages = tuple(json.loads(payload))
            except json.JSONDecodeError:
                logger.warning("Corrupt session payload for chat %s; discarding", conversation_id)
                return None
            try:
                tainted_by = tuple(json.loads(taint or "[]"))
            except json.JSONDecodeError:
                # Unreadable means unknown, and unknown must mean
                # suspicious: the safe default is the restrictive one.
                tainted_by = ("unreadable",)
            return Session(
                session_id,
                conversation_id,
                created_at,
                last_active_at,
                messages,
                turns,
                tainted_by,
            )

    def history(self, conversation_id: str, now: float | None = None) -> list[Mapping[str, Any]]:
        session = self.active(conversation_id, now)
        return list(session.messages) if session else []

    # -- writes -------------------------------------------------------

    def save(
        self,
        conversation_id: str,
        messages: Iterable[Mapping[str, Any]],
        *,
        now: float | None = None,
        tainted_by: Iterable[str] = (),
    ) -> Session:
        """Persist history for a chat, creating or extending its session."""
        moment = _moment(now)
        cleaned = close_open_tool_calls(list(messages))
        trimmed = trim_history(
            cleaned, max_messages=self.max_messages, max_bytes=self.max_bytes
        )
        payload = json.dumps(trimmed, default=str)

        with self._lock:
            row = self._connection.execute(
                "SELECT session_id, created_at, last_active_at, turns, tainted_by "
                "FROM sessions WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()

            if row is None or (moment - row[2]) >= self.ttl_seconds:
                session_id = uuid.uuid4().hex[:12]
                created_at = moment
                turns = 1
                carried: tuple[str, ...] = ()
            else:
                session_id, created_at, _, turns = row[0], row[1], row[2], row[3] + 1
                try:
                    carried = tuple(json.loads(row[4] or "[]"))
                except json.JSONDecodeError:
                    carried = ("unreadable",)

            # Accumulated, never replaced. A turn that happens to read
            # nothing untrusted does not clear what an earlier one did:
            # that text is still in the history being replayed.
            taint = tuple(dict.fromkeys([*carried, *(str(name) for name in tainted_by)]))

            self._connection.execute(
                "INSERT INTO sessions "
                "(conversation_id, session_id, created_at, last_active_at, turns, messages, "
                "tainted_by) "
                "VALUES (?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(conversation_id) DO UPDATE SET "
                "session_id=excluded.session_id, created_at=excluded.created_at, "
                "last_active_at=excluded.last_active_at, turns=excluded.turns, "
                "messages=excluded.messages, tainted_by=excluded.tainted_by",
                (
                    conversation_id,
                    session_id,
                    created_at,
                    moment,
                    turns,
                    payload,
                    json.dumps(list(taint)),
                ),
            )
            self._connection.commit()

        return Session(
            session_id, conversation_id, created_at, moment, tuple(trimmed), turns, taint
        )

    def reset(self, conversation_id: str) -> bool:
        """End the current session. Returns True if one was active."""
        with self._lock:
            cursor = self._connection.execute(
                "DELETE FROM sessions WHERE conversation_id = ?", (conversation_id,)
            )
            self._connection.commit()
            return cursor.rowcount > 0

    def purge_expired(self, now: float | None = None) -> int:
        moment = _moment(now)
        with self._lock:
            cursor = self._connection.execute(
                "DELETE FROM sessions WHERE ? - last_active_at >= ?",
                (moment, self.ttl_seconds),
            )
            self._connection.commit()
            return cursor.rowcount

    def close(self) -> None:
        with self._lock:
            self._connection.close()
