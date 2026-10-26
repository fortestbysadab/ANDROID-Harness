package com.androidharness.sandbox

/**
 * The shell program that actually contains a run, executed inside Termux.
 *
 * It is sent inline with `sh -c` rather than installed as a file, because
 * this app cannot write into Termux's private directory: they run as
 * different Android users, which is the whole reason the sandbox lives
 * there. The script is passed base64-encoded as an argument so that no
 * quoting of model-written code is ever performed.
 *
 * ## How files get out
 *
 * The sandboxed script cannot reach shared storage - `/sdcard` and
 * `/storage` are empty directories from inside proot. Instead the
 * **wrapper**, which runs outside proot after the script has exited,
 * copies out only the filenames the caller declared in advance.
 *
 * That makes egress mediated rather than ambient: a script cannot decide
 * for itself to write something into the owner's photo library.
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

    /**
     * Metadata lines, emitted **after** the exit marker.
     *
     * Position matters. Parsing takes everything before the *last* exit
     * marker as script output, so anything the wrapper prints afterwards
     * cannot be forged by the script: the script's own output is always
     * earlier in the stream.
     */
    const val OUTPUT_MARKER = "__HARNESS_OUTPUT__"
    const val OUTPUT_DIR_MARKER = "__HARNESS_OUTDIR__"
    const val NO_STORAGE_MARKER = "__HARNESS_NO_STORAGE__"

    /** Wrapper failures, kept well away from ordinary script exit codes. */
    const val EXIT_WORKSPACE_FAILED = 90
    const val EXIT_DECODE_FAILED = 91
    const val EXIT_PROOT_MISSING = 92

    /** `timeout` reports a killed process this way. */
    const val EXIT_TIMED_OUT = 124

    /** Where the wrapper copies declared outputs, visible to both sides. */
    const val SHARED_ROOT = "/storage/emulated/0/AndroidHarness/runs"

    val SCRIPT: String = """
        set -u
        B64="${'$'}1"
        WALL="${'$'}2"
        CPU="${'$'}3"
        AS_KB="${'$'}4"
        FSIZE_KB="${'$'}5"
        INTERP="${'$'}6"
        OUTPUTS="${'$'}7"

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

        # Everything below is wrapper territory, outside proot, and is
        # printed after the exit marker so a script cannot forge it.
        echo "$EXIT_MARKER ${'$'}STATUS"

        if [ "${'$'}STATUS" = "0" ] && [ -n "${'$'}OUTPUTS" ]; then
            OUT_DIR="$SHARED_ROOT/${'$'}RUN_ID"
            if mkdir -p "${'$'}OUT_DIR" 2>/dev/null; then
                COPIED=0
                for NAME in ${'$'}OUTPUTS; do
                    if [ -f "${'$'}WORK/${'$'}NAME" ]; then
                        if cp "${'$'}WORK/${'$'}NAME" "${'$'}OUT_DIR/${'$'}NAME" 2>/dev/null; then
                            echo "$OUTPUT_MARKER ${'$'}NAME"
                            COPIED=1
                        fi
                    fi
                done
                if [ "${'$'}COPIED" = "1" ]; then
                    echo "$OUTPUT_DIR_MARKER ${'$'}OUT_DIR"
                else
                    # Leave nothing behind in the owner's storage for a run
                    # that produced nothing.
                    rmdir "${'$'}OUT_DIR" 2>/dev/null || true
                fi
            else
                # Termux has not been granted storage access, so there is
                # nowhere both sides can see.
                echo "$NO_STORAGE_MARKER"
            fi
        fi

        exit 0
    """.trimIndent()
}
