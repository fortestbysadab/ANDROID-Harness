"""Tests for the approval store.

Kept outside app/src/main/python on purpose: that directory is packaged
into the APK, and test code has no business shipping to a phone.
"""

from __future__ import annotations

import threading
import time

import pytest

from harness_core.approvals import ApprovalRefused, ApprovalStore, argument_hash

SEND = {"to": "Ravi", "body": "I am running late"}


def make_store(tmp_path, **kwargs) -> ApprovalStore:
    return ApprovalStore(str(tmp_path / "approvals.sqlite3"), **kwargs)


def create(store: ApprovalStore, arguments=SEND, actor="owner"):
    return store.create(
        actor_id=actor,
        run_id="run-1",
        tool="send_message",
        tool_version="1",
        arguments=arguments,
        reason="sending a message to Ravi",
    )


def spend(store: ApprovalStore, approval, arguments=SEND, actor="owner"):
    return store.consume(
        approval.approval_id,
        actor_id=actor,
        tool="send_message",
        tool_version="1",
        arguments=arguments,
    )


class TestTheHash:
    def test_argument_order_does_not_change_it(self):
        assert argument_hash("t", "1", {"a": 1, "b": 2}) == argument_hash("t", "1", {"b": 2, "a": 1})

    def test_the_tool_version_is_part_of_it(self):
        # A tool whose behaviour changed is not the tool that was approved.
        assert argument_hash("t", "1", SEND) != argument_hash("t", "2", SEND)

    def test_a_changed_argument_changes_it(self):
        assert argument_hash("t", "1", SEND) != argument_hash("t", "1", {**SEND, "to": "Anil"})


class TestSpending:
    def test_an_approval_can_be_spent(self, tmp_path):
        store = make_store(tmp_path)
        approval = create(store)
        assert spend(store, approval).approval_id == approval.approval_id

    def test_it_cannot_be_spent_twice(self, tmp_path):
        store = make_store(tmp_path)
        approval = create(store)
        spend(store, approval)
        with pytest.raises(ApprovalRefused) as refusal:
            spend(store, approval)
        assert refusal.value.code == "approval_already_used"

    def test_it_will_not_authorise_a_different_action(self, tmp_path):
        # The point of the whole design. The owner approved a message to
        # Ravi; this tries to redeem it for a message to someone else.
        store = make_store(tmp_path)
        approval = create(store)
        with pytest.raises(ApprovalRefused) as refusal:
            spend(store, approval, arguments={**SEND, "to": "Anil"})
        assert refusal.value.code == "approval_action_changed"

    def test_it_will_not_authorise_a_newer_version_of_the_tool(self, tmp_path):
        store = make_store(tmp_path)
        approval = create(store)
        with pytest.raises(ApprovalRefused) as refusal:
            store.consume(
                approval.approval_id,
                actor_id="owner",
                tool="send_message",
                tool_version="2",
                arguments=SEND,
            )
        assert refusal.value.code == "approval_action_changed"

    def test_someone_else_cannot_spend_it(self, tmp_path):
        store = make_store(tmp_path)
        approval = create(store)
        with pytest.raises(ApprovalRefused) as refusal:
            spend(store, approval, actor="stranger")
        assert refusal.value.code == "approval_wrong_actor"

    def test_an_unknown_id_is_refused(self, tmp_path):
        store = make_store(tmp_path)
        with pytest.raises(ApprovalRefused) as refusal:
            store.consume(
                "made-up",
                actor_id="owner",
                tool="send_message",
                tool_version="1",
                arguments=SEND,
            )
        assert refusal.value.code == "approval_unknown"

    def test_it_expires(self, tmp_path):
        store = make_store(tmp_path, ttl_seconds=0)
        approval = create(store)
        time.sleep(0.01)
        with pytest.raises(ApprovalRefused) as refusal:
            spend(store, approval)
        assert refusal.value.code == "approval_expired"


class TestDurability:
    def test_an_approval_survives_the_process_dying(self, tmp_path):
        # The defect this store exists to fix. Android restarts background
        # processes freely; an approval must outlive that.
        path = str(tmp_path / "approvals.sqlite3")
        first = ApprovalStore(path)
        approval = create(first)
        first.close()

        second = ApprovalStore(path)
        assert spend(second, approval).approval_id == approval.approval_id

    def test_spentness_survives_too(self, tmp_path):
        path = str(tmp_path / "approvals.sqlite3")
        first = ApprovalStore(path)
        approval = create(first)
        spend(first, approval)
        first.close()

        second = ApprovalStore(path)
        with pytest.raises(ApprovalRefused) as refusal:
            spend(second, approval)
        assert refusal.value.code == "approval_already_used"


class TestRaces:
    def test_only_one_of_many_simultaneous_attempts_wins(self, tmp_path):
        store = make_store(tmp_path)
        approval = create(store)

        wins: list[str] = []
        losses: list[str] = []
        begin = threading.Barrier(8)

        def attempt() -> None:
            begin.wait()
            try:
                spend(store, approval)
                wins.append("ok")
            except ApprovalRefused as refusal:
                losses.append(refusal.code)

        threads = [threading.Thread(target=attempt) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        assert len(wins) == 1, f"approval spent {len(wins)} times"
        assert len(losses) == 7
        assert set(losses) == {"approval_already_used"}


class TestListing:
    def test_pending_shows_only_live_approvals_for_that_actor(self, tmp_path):
        store = make_store(tmp_path)
        mine = create(store)
        create(store, actor="someone-else")
        spent = create(store, arguments={"to": "Ravi", "body": "other"})
        spend(store, spent, arguments={"to": "Ravi", "body": "other"})

        pending = store.pending("owner")
        assert [item.approval_id for item in pending] == [mine.approval_id]

    def test_purge_removes_long_dead_rows(self, tmp_path):
        store = make_store(tmp_path, ttl_seconds=0)
        create(store)
        assert store.purge_expired(older_than_seconds=0) == 1
        assert store.pending("owner") == []
