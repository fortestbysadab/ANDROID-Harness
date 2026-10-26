package com.androidharness.sandbox

import android.content.Context
import android.util.Base64
import android.util.Log
import com.androidharness.termux.CommandResult
import com.androidharness.termux.TermuxBridge
import com.androidharness.termux.TermuxConstants
import com.androidharness.termux.TermuxStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs model-written code inside Termux, under proot.
 *
 * This is the **only** class permitted to import com.androidharness.termux.
 * Everything else in the app depends on the CodeSandbox interface, so that
 * Termux stays a provider rather than a foundation.
 *
 * Two layers of containment, and they are not the same thing:
 *
 *  - **proot** hides the owner's files and Termux's own home. It is a
 *    ptrace-based path illusion, not a security boundary; a determined
 *    escape is possible. Its job is preventing accidents.
 *  - **The Android UID** is the real boundary. Termux runs as a different
 *    user than this app, so code that defeats proot still cannot read this
 *    app's keys, session store or approvals. That protection comes free
 *    with running the code over here, and is the main reason it does.
 */
class TermuxSandbox(private val context: Context) : CodeSandbox {

    override val name: String = "termux"

    override fun availability(): Availability {
        val state = TermuxStatus.check(context)
        return Availability(
            usable = state.usable,
            detail = state.detail,
            remedy = state.remedy,
        )
    }

    /**
     * Blocks until the run finishes. Never call this from the main thread:
     * the result arrives through a PendingIntent delivered on that thread,
     * so blocking it would deadlock until the timeout.
     */
    override fun run(request: SandboxRequest, limits: SandboxLimits): SandboxOutcome {
        val availability = availability()
        if (!availability.usable) {
            return SandboxOutcome(
                succeeded = false,
                stdout = "",
                stderr = "",
                errorCode = SandboxErrors.UNAVAILABLE,
                remedy = availability.remedy.ifEmpty { availability.detail },
            )
        }

        val rejected = request.expectedOutputs.filterNot(OutputNames::isSafe)
        if (rejected.isNotEmpty()) {
            return SandboxOutcome(
                succeeded = false,
                stdout = "",
                stderr = "",
                errorCode = SandboxErrors.OUTPUT_MISSING,
                remedy = "These output names are not usable as filenames: " +
                    rejected.joinToString(", "),
            )
        }

        val encoded = Base64.encodeToString(
            request.script.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

        var received: CommandResult? = null
        val finished = CountDownLatch(1)

        TermuxBridge.run(
            context = context,
            executable = "${TermuxConstants.BIN}/sh",
            arguments = listOf(
                "-c",
                SandboxRunner.SCRIPT,
                "harness",
                encoded,
                limits.wallClockSeconds.toString(),
                limits.cpuSeconds.toString(),
                (limits.addressSpaceBytes / 1024).toString(),
                (limits.fileSizeBytes / 1024).toString(),
                request.interpreter,
                OutputNames.encode(request.expectedOutputs),
            ),
            // Give the wrapper room beyond the script's own wall clock, so
            // that a script killed by `timeout` reports as a script
            // timeout rather than as Termux failing to answer.
            timeoutMillis = (limits.wallClockSeconds + 15) * 1000L,
        ) { result ->
            received = result
            finished.countDown()
        }

        if (!finished.await(limits.wallClockSeconds + 30L, TimeUnit.SECONDS)) {
            return SandboxOutcome(
                succeeded = false,
                stdout = "",
                stderr = "",
                errorCode = SandboxErrors.TIMEOUT,
                remedy = "Termux never answered. It may need \"Display over " +
                    "other apps\" to be started in the background.",
            )
        }

        val result = received ?: return SandboxOutcome(
            succeeded = false,
            stdout = "",
            stderr = "",
            errorCode = SandboxErrors.UNAVAILABLE,
            remedy = "Termux returned nothing.",
        )

        return interpret(result, request.expectedOutputs)
    }

    private fun interpret(
        result: CommandResult,
        expectedOutputs: List<String>,
    ): SandboxOutcome {
        if (result.blockedByExternalAppsPolicy) {
            return SandboxOutcome(
                succeeded = false,
                stdout = "",
                stderr = result.stderr,
                errorCode = SandboxErrors.DENIED,
                remedy = "In Termux run:\n" +
                    "  echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                    "  termux-reload-settings",
            )
        }
        if (result.timedOut) {
            return SandboxOutcome(
                succeeded = false,
                stdout = result.stdout,
                stderr = result.stderr,
                errorCode = SandboxErrors.TIMEOUT,
                remedy = "Termux did not answer in time.",
            )
        }
        if (result.errmsg.isNotEmpty()) {
            return SandboxOutcome(
                succeeded = false,
                stdout = result.stdout,
                stderr = result.stderr,
                errorCode = SandboxErrors.UNAVAILABLE,
                remedy = result.errmsg,
            )
        }

        val parsed = ScriptExit.from(result.stdout)
        if (parsed == null) {
            // The wrapper always prints the marker, so its absence means
            // the wrapper itself never got that far.
            Log.w(TAG, "no exit marker; wrapper exit=${result.exitCode}")
            return SandboxOutcome(
                succeeded = false,
                stdout = result.stdout,
                stderr = result.stderr,
                errorCode = SandboxErrors.UNAVAILABLE,
                remedy = wrapperRemedy(result.exitCode),
            )
        }

        if (parsed.status == 0) {
            return collectOutputs(parsed.output, result.stderr, result.stdout, expectedOutputs)
        }
        if (parsed.status == SandboxRunner.EXIT_TIMED_OUT) {
            return SandboxOutcome(
                succeeded = false,
                stdout = parsed.output,
                stderr = result.stderr,
                errorCode = SandboxErrors.TIMEOUT,
                remedy = "The script ran past its time limit and was stopped.",
            )
        }
        return SandboxOutcome(
            succeeded = false,
            stdout = parsed.output,
            stderr = result.stderr,
            errorCode = SandboxErrors.SCRIPT_FAILED,
            remedy = "The script exited with status ${parsed.status}.",
        )
    }

    /**
     * Reads back whatever the wrapper copied into shared storage.
     *
     * The script never had access to this directory; the wrapper put the
     * declared files there after proot exited. Reading them needs
     * all-files access, which the owner grants once.
     */
    private fun collectOutputs(
        output: String,
        stderr: String,
        rawStdout: String,
        expectedOutputs: List<String>,
    ): SandboxOutcome {
        if (expectedOutputs.isEmpty()) {
            return SandboxOutcome(succeeded = true, stdout = output, stderr = stderr)
        }

        val report = WrapperReport.from(rawStdout)
        if (report.storageUnavailable) {
            return SandboxOutcome(
                succeeded = false,
                stdout = output,
                stderr = stderr,
                errorCode = SandboxErrors.OUTPUT_MISSING,
                remedy = "Termux cannot reach shared storage. Run " +
                    "`termux-setup-storage` in Termux and grant the " +
                    "permission it asks for.",
            )
        }

        val directory = report.outputDirectory
        if (directory == null) {
            return SandboxOutcome(
                succeeded = false,
                stdout = output,
                stderr = stderr,
                errorCode = SandboxErrors.OUTPUT_MISSING,
                remedy = "The sandbox produced none of the expected files.",
            )
        }

        if (!SharedStorage.readable()) {
            return SandboxOutcome(
                succeeded = false,
                stdout = output,
                stderr = stderr,
                errorCode = SandboxErrors.OUTPUT_MISSING,
                remedy = SharedStorage.REMEDY,
            )
        }

        val collected = LinkedHashMap<String, ByteArray>()
        val missing = mutableListOf<String>()
        expectedOutputs.forEach { name ->
            val bytes = SharedStorage.read(directory, name)
            if (bytes == null) missing.add(name) else collected[name] = bytes
        }

        if (missing.isNotEmpty()) {
            return SandboxOutcome(
                succeeded = false,
                stdout = output,
                stderr = stderr,
                outputs = collected,
                errorCode = SandboxErrors.OUTPUT_MISSING,
                remedy = "The script did not produce: ${missing.joinToString(", ")}",
            )
        }

        return SandboxOutcome(
            succeeded = true,
            stdout = output,
            stderr = stderr,
            outputs = collected,
        )
    }

    private fun wrapperRemedy(exitCode: Int?): String = when (exitCode) {
        SandboxRunner.EXIT_PROOT_MISSING ->
            "proot is not installed in Termux. Run: pkg install proot"
        SandboxRunner.EXIT_WORKSPACE_FAILED ->
            "Could not create a workspace in Termux's tmp directory."
        SandboxRunner.EXIT_DECODE_FAILED ->
            "The script could not be decoded inside Termux."
        else -> "The sandbox wrapper failed before the script ran."
    }

    private companion object {
        const val TAG = "TermuxSandbox"
    }
}
