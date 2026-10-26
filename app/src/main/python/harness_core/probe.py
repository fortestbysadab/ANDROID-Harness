"""Proof that the embedded interpreter is real and usable.

Checks three things that the core will depend on, rather than just
printing a greeting:

  * the interpreter runs and reports its version
  * the standard library is present and importable, including the
    compiled modules that are easy to lose when a stdlib is repackaged
    into an APK
  * the app's private directory is writable, since sessions, approvals
    and the audit log all have to be durable
"""

import json
import os
import platform
import sqlite3
import sys


def report(writable_dir: str) -> str:
    """Return a JSON string; the caller is Kotlin, so keep it simple."""
    findings = {
        "python": sys.version.split()[0],
        "implementation": platform.python_implementation(),
        "abi": platform.machine(),
    }

    # sqlite3 and hashlib are compiled modules. If a repackaged stdlib is
    # going to be missing something, it is one of these, and the core
    # needs both - durable approvals are a database, and an approval is
    # bound to a sha256.
    try:
        import hashlib

        connection = sqlite3.connect(":memory:")
        connection.execute("create table t (x text)")
        connection.execute("insert into t values ('ok')")
        row = connection.execute("select x from t").fetchone()
        connection.close()
        findings["sqlite"] = f"{sqlite3.sqlite_version} ({row[0]})"
        findings["sha256"] = hashlib.sha256(b"harness").hexdigest()[:16]
    except Exception as error:  # noqa: BLE001 - the diagnosis is the point
        findings["stdlib_error"] = f"{type(error).__name__}: {error}"

    # Durability check. An in-memory approval store was a known defect in
    # the predecessor; whatever replaces it has to land on disk.
    try:
        path = os.path.join(writable_dir, "core-probe.txt")
        with open(path, "w") as handle:
            handle.write("written by the embedded interpreter\n")
        with open(path) as handle:
            findings["private_dir"] = "writable" if handle.read() else "empty read"
        os.remove(path)
    except Exception as error:  # noqa: BLE001
        findings["private_dir"] = f"{type(error).__name__}: {error}"

    return json.dumps(findings, indent=2)
