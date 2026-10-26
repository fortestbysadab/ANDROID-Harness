package com.androidharness.core

import android.content.Context
import android.os.SystemClock
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * CPython running inside this app's own process.
 *
 * Two interpreters now exist, and conflating them would undo the whole
 * design:
 *
 *  - **This one** runs the agent core - planner, policy, registry,
 *    sessions, approvals, audit log. It is our code, it holds the keys,
 *    and it is trusted.
 *  - **The sandbox** runs model-written code, in Termux, under a
 *    different Android user. Nothing written by a model is ever executed
 *    here, because in-process means same-UID means it could read
 *    everything the app can.
 *
 * Starting the interpreter is a one-time cost that must not happen on
 * the main thread.
 */
object AgentCore {

    /** Timings in milliseconds, because this is also the Phase 2a spike. */
    data class Report(val text: String, val startupMillis: Long, val callMillis: Long)

    @Volatile
    private var startupMillis: Long = -1

    /**
     * Safe to call repeatedly; the interpreter can only be started once
     * per process, and a second attempt would throw.
     */
    private fun ensureStarted(context: Context): Long {
        if (Python.isStarted()) return startupMillis
        synchronized(this) {
            if (!Python.isStarted()) {
                val began = SystemClock.elapsedRealtime()
                Python.start(AndroidPlatform(context.applicationContext))
                startupMillis = SystemClock.elapsedRealtime() - began
            }
        }
        return startupMillis
    }

    /**
     * Ask the configured provider which models it will serve.
     *
     * Typing a model name by hand is the commonest way to get an opaque
     * 404, so the app offers the list rather than asking the owner to
     * remember it. Blocking; call it off the main thread.
     */
    fun listModels(context: Context, baseUrl: String, apiKey: String): Result<List<String>> {
        ensureStarted(context)
        return try {
            val names = Python.getInstance()
                .getModule("harness_core.llm")
                .callAttr("list_models", baseUrl, apiKey.ifBlank { null })
                .asList()
                .map { it.toString() }
            Result.success(names)
        } catch (failure: PyException) {
            Result.failure(IllegalStateException(describe(failure), failure))
        }
    }

    /** Send one real request, to prove the whole path works end to end. */
    fun testModel(
        context: Context,
        baseUrl: String,
        model: String,
        apiKey: String,
    ): Result<String> {
        ensureStarted(context)
        return try {
            val reply = Python.getInstance()
                .getModule("harness_core.selfcheck")
                .callAttr("ask_the_model", baseUrl, model, apiKey.ifBlank { null })
                .toString()
            Result.success(reply)
        } catch (failure: PyException) {
            Result.failure(IllegalStateException(describe(failure), failure))
        }
    }

    /**
     * Pull the useful line out of a Python traceback.
     *
     * The planner raises errors that already carry an owner-facing
     * remedy; burying that under forty lines of stack would waste it.
     */
    private fun describe(failure: PyException): String {
        val message = failure.message.orEmpty()
        val lastLine = message.trim().lines().lastOrNull().orEmpty()
        return lastLine.substringAfter("Error: ", lastLine).ifBlank { message.take(400) }
    }

    /** Runs the approval store through its whole life cycle on-device. */
    fun selfCheckApprovals(context: Context): String {
        ensureStarted(context)
        return try {
            Python.getInstance()
                .getModule("harness_core.selfcheck")
                .callAttr("approvals", context.filesDir.absolutePath)
                .toString()
        } catch (failure: PyException) {
            "python raised:\n${failure.message}"
        }
    }

    /**
     * Asks the core to describe itself. Never call from the main thread:
     * the first call pays for extracting and loading a whole stdlib.
     */
    fun probe(context: Context): Report {
        val startup = ensureStarted(context)
        val began = SystemClock.elapsedRealtime()
        val text = try {
            Python.getInstance()
                .getModule("harness_core.probe")
                .callAttr("report", context.filesDir.absolutePath)
                .toString()
        } catch (failure: PyException) {
            // A Python traceback is the most useful thing we could show
            // here, so it is surfaced rather than swallowed.
            "python raised:\n${failure.message}"
        }
        return Report(text, startup, SystemClock.elapsedRealtime() - began)
    }
}
