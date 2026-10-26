package com.androidharness.sandbox

/**
 * The shell program that actually contains a run, executed inside Termux.
 *
 * It is sent inline with `sh -c` rather than installed as a file, because
 * this app cannot write into Termux's private directory: they run as
 * different Android users, which is the whole reason the sandbox lives
 * there. The script is passed base64-encoded as an argument so that no
 * quoting of model-written code is ever performed.
 */
object SandboxRunner {

    /**
     * Printed after the sandboxed process exits, carrying its status.
     *
     * The shell wrapper always exits 0, so that "the wrapper broke" and
     * "the script failed" are distinguishable instead of collapsing into
     * one non-zero code.
     */
    const val EXIT_MARKER = "__HARNESS_EXIT__"

    /** Wrapper failures, kept well away from ordinary script exit codes. */
    const val EXIT_WORKSPACE_FAILED = 90
    const val EXIT_DECODE_FAILED = 91
    const val EXIT_PROOT_MISSING = 92

    /** `timeout` reports a killed process this way. */
    const val EXIT_TIMED_OUT = 124

    val SCRIPT: String = """
        set -u
        B64="${'$'}1"
        WALL="${'$'}2"
        CPU="${'$'}3"
        AS_KB="${'$'}4"
        FSIZE_KB="${'$'}5"
        INTERP="${'$'}6"

        PREFIX=/data/data/com.termux/files/usr

        command -v proot >/dev/null 2>&1 || exit $EXIT_PROOT_MISSING

        RUN_ID="harness-${'$'}${'$'}-${'$'}(date +%s)"
        WORK="${'$'}PREFIX/tmp/${'$'}RUN_ID"
        EMPTY="${'$'}PREFIX/tmp/${'$'}RUN_ID-empty"

        # A fresh workspace per run. Reusing one made a revision that wrote
        # nothing look identical to a script that wrote the same file again.
        mkdir -p "${'$'}WORK" "${'$'}EMPTY" || exit $EXIT_WORKSPACE_FAILED
        cleanup() { rm -rf "${'$'}WORK" "${'$'}EMPTY"; }
        trap cleanup EXIT

        printf '%s' "${'$'}B64" | base64 -d > "${'$'}WORK/script.py" || exit $EXIT_DECODE_FAILED

        # Resource ceilings. RLIMIT_NPROC is deliberately absent: it is a
        # per-user cap, so it cannot bound a child and setting it low only
        # stops the child starting at all.
        ulimit -t "${'$'}CPU" 2>/dev/null || true
        ulimit -f "${'$'}FSIZE_KB" 2>/dev/null || true
        ulimit -v "${'$'}AS_KB" 2>/dev/null || true

        # Empty directories bound over the interesting paths. The script
        # sees them, finds nothing, and cannot reach the owner's files or
        # Termux's own home - where the predecessor kept its .env.
        timeout -k 5 "${'$'}WALL" proot \
            -b "${'$'}EMPTY:${'$'}HOME" \
            -b "${'$'}EMPTY:/sdcard" \
            -b "${'$'}EMPTY:/storage" \
            -b "${'$'}WORK:${'$'}WORK" \
            -w "${'$'}WORK" \
            "${'$'}INTERP" "${'$'}WORK/script.py"
        STATUS=${'$'}?

        echo "$EXIT_MARKER ${'$'}STATUS"
        exit 0
    """.trimIndent()
}
