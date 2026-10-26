package com.androidharness.sandbox

/**
 * Somewhere isolated to run model-written code.
 *
 * This interface exists so that Termux is a *provider*, not a foundation.
 * The predecessor project proved the value of this shape: swapping the
 * screen-automation backend from ADB to an installed app was a one-line
 * change at the composition root because everything above it spoke to a
 * protocol rather than to an implementation.
 *
 * Planned implementations:
 *
 *  - `TermuxSandbox`  - proot inside Termux, under a separate Android UID.
 *  - `NullSandbox`    - honestly unavailable; the tool reports a remedy.
 *  - later, if Termux proves awkward: WebAssembly in a WebView, or an
 *    `isolatedProcess` service.
 *
 * Nothing in the app may import `com.androidharness.termux` except the
 * Termux implementation of this interface.
 */
interface CodeSandbox {

    val name: String

    /** Whether work can be accepted, and if not, what the owner can do. */
    fun availability(): Availability

    /**
     * Run a script to completion inside the sandbox.
     *
     * Implementations must enforce [limits] themselves; callers are not
     * trusted to bound their own work, because the script is written by a
     * model from input that may be hostile.
     */
    fun run(request: SandboxRequest, limits: SandboxLimits): SandboxOutcome
}

data class Availability(val usable: Boolean, val detail: String, val remedy: String = "")

data class SandboxRequest(
    val script: String,
    val interpreter: String = "python",
    /** Files the script may read, copied in. */
    val inputs: Map<String, ByteArray> = emptyMap(),
    /** Filenames the script is expected to produce, copied out. */
    val expectedOutputs: List<String> = emptyList(),
)

/**
 * Deliberately conservative. The predecessor learned that RLIMIT_NPROC is
 * a per-user cap and cannot bound a child process - setting it low only
 * stopped the child starting - and that 1 GB of address space is too
 * tight for the common document libraries.
 */
data class SandboxLimits(
    val wallClockSeconds: Int = 60,
    val cpuSeconds: Int = 50,
    val addressSpaceBytes: Long = 4L * 1024 * 1024 * 1024,
    val fileSizeBytes: Long = 64L * 1024 * 1024,
)

data class SandboxOutcome(
    val succeeded: Boolean,
    val stdout: String,
    val stderr: String,
    val outputs: Map<String, ByteArray> = emptyMap(),
    /** A named code, never a bare message. */
    val errorCode: String = "",
    val remedy: String = "",
)

/** Every way a sandbox run can fail, named. */
object SandboxErrors {
    const val UNAVAILABLE = "sandbox_unavailable"
    const val DENIED = "sandbox_denied"
    const val TIMEOUT = "sandbox_timeout"
    const val SCRIPT_FAILED = "sandbox_script_failed"
    const val OUTPUT_MISSING = "sandbox_output_missing"
    const val LIMIT_EXCEEDED = "sandbox_limit_exceeded"
}
