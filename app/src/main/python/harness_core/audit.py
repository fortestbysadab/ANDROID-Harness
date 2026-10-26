"""The audit log: what the agent did, and why it was allowed to.

Ported from the predecessor with one defect fixed. There the JSONL sink
appended for ever. On a server that is someone else's problem; on a
phone it is a file that quietly grows until storage runs out, and the
owner has no idea why. It now rotates.

Rotation is by size, keeping a fixed number of generations, so the log's
total footprint has a hard ceiling that can be stated: `max_bytes x
(keep + 1)`. The default ceiling is 5 MB.

One rule survives unchanged and matters more than the rotation: **the
caller passes redacted fields**. The sink will not inspect values
looking for secrets, because a redactor that works by guessing is a
redactor that will eventually guess wrong and write an API key to disk.
Deciding what is safe to record belongs with the code that knows what
the value is.
"""

from __future__ import annotations

import json
import os
import threading
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol


class AuditSink(Protocol):
    def emit(self, event: str, fields: Mapping[str, Any]) -> None: ...


class NullAuditSink:
    def emit(self, event: str, fields: Mapping[str, Any]) -> None:
        pass


@dataclass
class MemoryAuditSink:
    """For tests, and for showing the owner what just happened."""

    events: list[dict[str, Any]] = field(default_factory=list)

    def emit(self, event: str, fields: Mapping[str, Any]) -> None:
        self.events.append({"timestamp": _now(), "event": event, **dict(fields)})

    def names(self) -> list[str]:
        return [event["event"] for event in self.events]


class JsonlAuditSink:
    """Append-only JSONL, rotated by size.

    Generations are named `audit.jsonl`, `audit.jsonl.1`, `audit.jsonl.2`
    and so on, with `.1` always the most recent rollover - the convention
    logrotate established, so the files are readable by anyone who has
    ever looked at a Unix log.
    """

    def __init__(
        self,
        path: str,
        *,
        max_bytes: int = 1024 * 1024,
        keep: int = 4,
    ) -> None:
        if max_bytes <= 0:
            raise ValueError("max_bytes must be positive")
        if keep < 0:
            raise ValueError("keep cannot be negative")
        self.path = os.path.realpath(os.path.expanduser(path))
        self.max_bytes = int(max_bytes)
        self.keep = int(keep)
        parent = os.path.dirname(self.path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.Lock()

    def emit(self, event: str, fields: Mapping[str, Any]) -> None:
        record = {"timestamp": _now(), "event": event, **dict(fields)}
        line = json.dumps(record, separators=(",", ":"), sort_keys=True, default=str) + "\n"
        encoded = line.encode("utf-8")
        with self._lock:
            # Rotate before writing, so a single record is never split
            # across two files. A record larger than the limit still goes
            # in whole: a truncated JSON line is worse than an oversized
            # file, because it cannot be read back at all.
            if self._size() + len(encoded) > self.max_bytes and self._size() > 0:
                self._rotate()
            with open(self.path, "ab") as handle:
                handle.write(encoded)

    def _size(self) -> int:
        try:
            return os.path.getsize(self.path)
        except OSError:
            return 0

    def _rotate(self) -> None:
        if self.keep == 0:
            os.remove(self.path)
            return
        # Oldest first, or each rename would overwrite the next one.
        # The oldest generation needs no explicit delete: os.replace
        # overwrites its destination, so renaming .{keep-1} onto .{keep}
        # discards it. A mutation test found the delete was dead code.
        for index in range(self.keep - 1, 0, -1):
            source = f"{self.path}.{index}"
            if os.path.exists(source):
                os.replace(source, f"{self.path}.{index + 1}")
        os.replace(self.path, f"{self.path}.1")

    def generations(self) -> list[str]:
        """Every log file that currently exists, newest first."""
        found = [self.path] if os.path.exists(self.path) else []
        found += [
            f"{self.path}.{index}"
            for index in range(1, self.keep + 1)
            if os.path.exists(f"{self.path}.{index}")
        ]
        return found

    def total_bytes(self) -> int:
        return sum(os.path.getsize(name) for name in self.generations())


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
