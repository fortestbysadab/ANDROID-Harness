"""Tests for the audit log, mostly about it not eating the phone."""

from __future__ import annotations

import json
import os

import pytest

from harness_core.audit import JsonlAuditSink


def sink(tmp_path, **kwargs):
    return JsonlAuditSink(str(tmp_path / "logs" / "audit.jsonl"), **kwargs)


def read_lines(path):
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


class TestWriting:
    def test_an_event_is_one_json_line(self, tmp_path):
        log = sink(tmp_path)
        log.emit("tool_finished", {"tool": "read_battery", "status": "ok"})
        records = read_lines(log.path)
        assert len(records) == 1
        assert records[0]["event"] == "tool_finished"
        assert records[0]["tool"] == "read_battery"
        assert "timestamp" in records[0]

    def test_the_directory_is_created(self, tmp_path):
        log = sink(tmp_path)
        assert os.path.isdir(os.path.dirname(log.path))

    def test_events_append(self, tmp_path):
        log = sink(tmp_path)
        for index in range(5):
            log.emit("tick", {"index": index})
        assert [r["index"] for r in read_lines(log.path)] == [0, 1, 2, 3, 4]


class TestRotation:
    """The defect this module was rewritten to fix: on a phone, a log
    that only ever grows is a slow storage leak the owner cannot explain."""

    def test_it_rotates_once_the_limit_is_passed(self, tmp_path):
        log = sink(tmp_path, max_bytes=300, keep=3)
        for index in range(40):
            log.emit("tick", {"index": index, "padding": "x" * 50})
        assert os.path.exists(f"{log.path}.1")

    def test_the_total_size_is_bounded(self, tmp_path):
        log = sink(tmp_path, max_bytes=500, keep=2)
        for index in range(500):
            log.emit("tick", {"index": index, "padding": "x" * 100})
        # The ceiling is max_bytes per generation, plus the live file,
        # plus the one record that was allowed to overshoot.
        assert log.total_bytes() <= 500 * 3 + 200
        assert len(log.generations()) <= 3

    def test_the_newest_rollover_is_dot_one(self, tmp_path):
        log = sink(tmp_path, max_bytes=200, keep=3)
        for index in range(60):
            log.emit("tick", {"index": index, "padding": "x" * 40})

        newest_rolled = read_lines(f"{log.path}.1")
        older = read_lines(f"{log.path}.2")
        assert older[-1]["index"] < newest_rolled[0]["index"]

    def test_the_oldest_generation_is_discarded(self, tmp_path):
        log = sink(tmp_path, max_bytes=200, keep=2)
        for index in range(100):
            log.emit("tick", {"index": index, "padding": "x" * 40})
        assert not os.path.exists(f"{log.path}.3")

    def test_a_record_is_never_split_across_files(self, tmp_path):
        # Every line in every generation must still parse as JSON.
        log = sink(tmp_path, max_bytes=250, keep=3)
        for index in range(200):
            log.emit("tick", {"index": index, "padding": "x" * 60})
        for name in log.generations():
            read_lines(name)  # raises if any line is truncated

    def test_an_oversized_record_is_written_whole(self, tmp_path):
        # Better one fat file than a line that cannot be read back.
        log = sink(tmp_path, max_bytes=100, keep=1)
        log.emit("huge", {"padding": "x" * 5000})
        assert len(read_lines(log.path)) == 1

    def test_keep_zero_just_truncates(self, tmp_path):
        log = sink(tmp_path, max_bytes=200, keep=0)
        for index in range(50):
            log.emit("tick", {"index": index, "padding": "x" * 40})
        assert log.generations() == [log.path]

    def test_nonsense_settings_are_refused(self, tmp_path):
        with pytest.raises(ValueError):
            sink(tmp_path, max_bytes=0)
        with pytest.raises(ValueError):
            sink(tmp_path, keep=-1)
