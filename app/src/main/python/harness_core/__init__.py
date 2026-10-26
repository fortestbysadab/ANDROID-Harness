"""The agent core, which will be ported here from the predecessor.

It is plain Python with no Android and no Termux assumptions, which is
the whole reason it can move across unchanged: the planner, policy
engine, tool registry, runtime loop, sessions, approvals and audit log
never knew what they were running on.

Nothing is ported yet. This package currently exists to answer one
question - does CPython actually start inside this app - before any of
that work is committed to it.
"""
