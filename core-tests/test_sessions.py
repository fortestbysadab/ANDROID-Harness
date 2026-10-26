"""Tests for conversation sessions.

The module came over from the predecessor, but its tests did not, and an
untested port is just hopeful code. Two of its invariants are not
cosmetic: break either and the next provider request is rejected
outright with a 400, which looks to the owner like the agent randomly
stopped working.
"""

from __future__ import annotations

import json

from harness_core.sessions import (
    SqliteSessionStore,
    close_open_tool_calls,
    trim_history,
)


def user(text):
    return {"role": "user", "content": text}


def assistant_calling(call_id, name="read_battery"):
    return {
        "role": "assistant",
        "content": "",
        "tool_calls": [
            {"id": call_id, "type": "function", "function": {"name": name, "arguments": "{}"}}
        ],
    }


def tool_reply(call_id, name="read_battery"):
    return {"role": "tool", "tool_call_id": call_id, "name": name, "content": "{}"}


def store(tmp_path, **kwargs):
    return SqliteSessionStore(str(tmp_path / "sessions.sqlite3"), **kwargs)


class TestUnansweredToolCalls:
    """An assistant message proposing a call with no reply is a protocol
    violation. It happens legitimately whenever a run pauses for approval."""

    def test_an_unanswered_call_is_closed_honestly(self):
        messages = [user("send it"), assistant_calling("c1", "send_message")]
        closed = close_open_tool_calls(messages)
        assert closed[-1]["role"] == "tool"
        assert closed[-1]["tool_call_id"] == "c1"
        assert json.loads(closed[-1]["content"])["status"] == "not_executed"

    def test_an_answered_call_is_left_alone(self):
        messages = [user("battery?"), assistant_calling("c1"), tool_reply("c1")]
        assert close_open_tool_calls(messages) == [dict(m) for m in messages]

    def test_every_call_in_one_message_gets_a_reply(self):
        message = {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "a", "arguments": "{}"}},
                {"id": "c2", "type": "function", "function": {"name": "b", "arguments": "{}"}},
            ],
        }
        closed = close_open_tool_calls([user("go"), message])
        assert [m.get("tool_call_id") for m in closed if m["role"] == "tool"] == ["c1", "c2"]


class TestTrimming:
    def test_old_turns_are_dropped_first(self):
        messages = []
        for index in range(10):
            messages += [user(f"question {index}"), {"role": "assistant", "content": "answer"}]
        trimmed = trim_history(messages, max_messages=6, max_bytes=10_000)
        assert trimmed[0]["content"] == "question 7"
        assert len(trimmed) == 6

    def test_a_tool_call_is_never_orphaned_by_trimming(self):
        # Cutting mid-turn would leave an assistant tool_call with no
        # reply, which the provider rejects.
        messages = [
            user("old"),
            {"role": "assistant", "content": "old answer"},
            user("battery?"),
            assistant_calling("c1"),
            tool_reply("c1"),
            {"role": "assistant", "content": "87%"},
        ]
        trimmed = trim_history(messages, max_messages=4, max_bytes=10_000)
        proposed = {
            call["id"]
            for message in trimmed
            if message["role"] == "assistant"
            for call in message.get("tool_calls", [])
        }
        answered = {m["tool_call_id"] for m in trimmed if m["role"] == "tool"}
        assert proposed == answered

    def test_the_newest_turn_survives_even_if_it_is_too_big(self):
        # Dropping the thing the owner just said would be worse than
        # going over budget.
        messages = [user("x" * 5000), {"role": "assistant", "content": "ok"}]
        assert trim_history(messages, max_messages=2, max_bytes=10) == messages

    def test_the_system_message_is_not_part_of_the_history(self):
        messages = [{"role": "system", "content": "rules"}, user("hi")]
        assert trim_history(messages) == [user("hi")]


class TestTheStore:
    def test_history_comes_back(self, tmp_path):
        sessions = store(tmp_path)
        sessions.save("chat-1", [user("hello")])
        assert sessions.history("chat-1")[0]["content"] == "hello"

    def test_an_unknown_conversation_is_empty_not_an_error(self, tmp_path):
        assert store(tmp_path).history("never-seen") == []

    def test_conversations_do_not_bleed_into_each_other(self, tmp_path):
        sessions = store(tmp_path)
        sessions.save("chat-1", [user("mine")])
        sessions.save("chat-2", [user("theirs")])
        assert sessions.history("chat-1")[0]["content"] == "mine"

    def test_it_survives_a_restart(self, tmp_path):
        path = str(tmp_path / "sessions.sqlite3")
        first = SqliteSessionStore(path)
        first.save("chat-1", [user("remember this")])
        first.close()
        assert SqliteSessionStore(path).history("chat-1")[0]["content"] == "remember this"

    def test_an_idle_session_expires(self, tmp_path):
        # Stale context must not leak into a new request hours later -
        # and tool output like contacts or location must not linger.
        sessions = store(tmp_path, ttl_seconds=60)
        sessions.save("chat-1", [user("hello")], now=1000.0)
        assert sessions.history("chat-1", now=1030.0) != []
        assert sessions.history("chat-1", now=1100.0) == []

    def test_turns_count_up_within_a_session(self, tmp_path):
        sessions = store(tmp_path)
        sessions.save("chat-1", [user("one")], now=1000.0)
        second = sessions.save("chat-1", [user("one"), user("two")], now=1010.0)
        assert second.turns == 2

    def test_a_new_session_starts_after_expiry(self, tmp_path):
        sessions = store(tmp_path, ttl_seconds=60)
        first = sessions.save("chat-1", [user("one")], now=1000.0)
        later = sessions.save("chat-1", [user("two")], now=2000.0)
        assert later.session_id != first.session_id
        assert later.turns == 1

    def test_reset_ends_the_session(self, tmp_path):
        sessions = store(tmp_path)
        sessions.save("chat-1", [user("hello")])
        assert sessions.reset("chat-1") is True
        assert sessions.history("chat-1") == []
        assert sessions.reset("chat-1") is False

    def test_purge_clears_only_the_dead_ones(self, tmp_path):
        sessions = store(tmp_path, ttl_seconds=60)
        sessions.save("old", [user("x")], now=1000.0)
        sessions.save("fresh", [user("y")], now=1990.0)
        assert sessions.purge_expired(now=2000.0) == 1
        assert sessions.history("fresh", now=2000.0) != []

    def test_saving_closes_an_unanswered_call(self, tmp_path):
        sessions = store(tmp_path)
        saved = sessions.save("chat-1", [user("send it"), assistant_calling("c1")])
        assert saved.messages[-1]["role"] == "tool"


class TestRememberingTaint:
    """Taint is stored beside the history, because it describes it.

    The two must travel together: the history is what gets replayed to
    the model, and the taint is the reason to be careful about it.
    Keeping them in separate places is how they drift apart.
    """

    def store(self, tmp_path, **kwargs):
        return SqliteSessionStore(str(tmp_path / "sessions.sqlite3"), **kwargs)

    def test_a_fresh_conversation_is_clean(self, tmp_path):
        store = self.store(tmp_path)
        session = store.save("c", [{"role": "user", "content": "hi"}])
        assert session.tainted is False
        assert session.tainted_by == ()

    def test_taint_is_written_and_read_back(self, tmp_path):
        store = self.store(tmp_path)
        store.save("c", [{"role": "user", "content": "hi"}], tainted_by=["get_notifications"])
        assert store.active("c").tainted_by == ("get_notifications",)

    def test_a_later_clean_save_does_not_clear_it(self, tmp_path):
        # The whole point. A turn that reads nothing untrusted does not
        # remove the untrusted text an earlier turn put in the history.
        store = self.store(tmp_path)
        store.save("c", [{"role": "user", "content": "hi"}], tainted_by=["get_notifications"])
        store.save("c", [{"role": "user", "content": "hi again"}])
        assert store.active("c").tainted_by == ("get_notifications",)

    def test_sources_accumulate_without_duplicating(self, tmp_path):
        store = self.store(tmp_path)
        store.save("c", [{"role": "user", "content": "a"}], tainted_by=["get_notifications"])
        store.save("c", [{"role": "user", "content": "b"}], tainted_by=["read_email"])
        store.save("c", [{"role": "user", "content": "c"}], tainted_by=["get_notifications"])
        assert store.active("c").tainted_by == ("get_notifications", "read_email")

    def test_resetting_clears_it(self, tmp_path):
        store = self.store(tmp_path)
        store.save("c", [{"role": "user", "content": "a"}], tainted_by=["get_notifications"])
        store.reset("c")
        store.save("c", [{"role": "user", "content": "b"}])
        assert store.active("c").tainted_by == ()

    def test_an_expired_session_starts_clean(self, tmp_path):
        # The history went with the session, so the reason for the
        # suspicion went too.
        store = self.store(tmp_path, ttl_seconds=10)
        store.save("c", [{"role": "user", "content": "a"}], tainted_by=["get_notifications"], now=0)
        session = store.save("c", [{"role": "user", "content": "b"}], now=1000)
        assert session.tainted_by == ()

    def test_two_conversations_do_not_share_taint(self, tmp_path):
        store = self.store(tmp_path)
        store.save("a", [{"role": "user", "content": "x"}], tainted_by=["get_notifications"])
        store.save("b", [{"role": "user", "content": "y"}])
        assert store.active("b").tainted_by == ()

    def test_an_older_database_without_the_column_is_migrated(self, tmp_path):
        # Shipping a change that drops the owner's conversations to make
        # room for a new column would be its own bug.
        import sqlite3

        path = tmp_path / "sessions.sqlite3"
        old = sqlite3.connect(str(path))
        old.execute(
            "CREATE TABLE sessions (conversation_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, "
            "created_at REAL NOT NULL, last_active_at REAL NOT NULL, "
            "turns INTEGER NOT NULL DEFAULT 0, messages TEXT NOT NULL)"
        )
        import time as _time

        now = _time.time()
        old.execute(
            "INSERT INTO sessions VALUES (?,?,?,?,?,?)",
            ("c", "old-session", now, now, 3, '[{"role": "user", "content": "kept"}]'),
        )
        old.commit()
        old.close()

        store = SqliteSessionStore(str(path))
        session = store.active("c")
        assert session is not None
        assert session.messages[0]["content"] == "kept"
        assert session.tainted_by == ()

    def test_unreadable_taint_is_treated_as_tainted(self, tmp_path):
        # Unknown must mean suspicious; the safe default is the
        # restrictive one.
        import sqlite3

        store = self.store(tmp_path)
        store.save("c", [{"role": "user", "content": "a"}])
        raw = sqlite3.connect(str(tmp_path / "sessions.sqlite3"))
        raw.execute("UPDATE sessions SET tainted_by = 'not json'")
        raw.commit()
        raw.close()
        assert store.active("c").tainted is True
