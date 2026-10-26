package com.androidharness.sandbox

/**
 * Reading the wrapper's report out of a single stdout stream.
 *
 * Script output and wrapper metadata share one channel, so the split has
 * to be unforgeable. The rule: everything before the **last** exit marker
 * is the script's, everything after it is the wrapper's. A script can
 * print whatever it likes, but it cannot print anything *after* the
 * wrapper does.
 */
internal data class ScriptExit(val output: String, val status: Int) {
    companion object {
        fun from(stdout: String): ScriptExit? {
            val marker = SandboxRunner.EXIT_MARKER
            val index = stdout.lastIndexOf(marker)
            if (index < 0) return null
            val status = stdout.substring(index + marker.length)
                .trim()
                .takeWhile { it.isDigit() }
                .toIntOrNull() ?: return null
            return ScriptExit(stdout.substring(0, index).trimEnd('\n'), status)
        }
    }
}

/** What the wrapper did with the declared outputs, after the script ended. */
internal data class WrapperReport(
    val copiedOutputs: List<String>,
    val outputDirectory: String?,
    val storageUnavailable: Boolean,
) {
    companion object {
        fun from(stdout: String): WrapperReport {
            val index = stdout.lastIndexOf(SandboxRunner.EXIT_MARKER)
            // Only the tail is trusted. Before the last exit marker is
            // script-controlled text and must never be read as metadata.
            val tail = if (index < 0) "" else stdout.substring(index)

            val copied = mutableListOf<String>()
            var directory: String? = null
            var noStorage = false

            tail.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith(SandboxRunner.OUTPUT_MARKER) ->
                        line.removePrefix(SandboxRunner.OUTPUT_MARKER).trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { copied.add(it) }

                    line.startsWith(SandboxRunner.OUTPUT_DIR_MARKER) ->
                        directory = line.removePrefix(SandboxRunner.OUTPUT_DIR_MARKER)
                            .trim()
                            .takeIf { it.isNotEmpty() }

                    line == SandboxRunner.NO_STORAGE_MARKER -> noStorage = true
                }
            }
            return WrapperReport(copied, directory, noStorage)
        }
    }
}

/**
 * Output filenames are chosen by the model, so they are untrusted input
 * that is about to become a path. Anything that could traverse, hide, or
 * confuse the shell loop that iterates them is refused outright rather
 * than sanitised into something plausible.
 */
internal object OutputNames {

    private val allowed = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun isSafe(name: String): Boolean =
        allowed.matches(name) && !name.contains("..")

    /** Space-separated, because the wrapper iterates them with `for`. */
    fun encode(names: List<String>): String = names.joinToString(" ")
}
