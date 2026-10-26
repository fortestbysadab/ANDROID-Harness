package com.androidharness.tools

import org.json.JSONObject

/**
 * A capability implemented in Kotlin and offered to the Python core.
 *
 * The boundary is two JSON strings, chosen so that nothing else has to
 * cross it: no Python objects reach Kotlin, no Android types reach the
 * core, and the core stays testable on a laptop with no device.
 *
 * A tool declares its risk once, at registration. It cannot revise that
 * at call time - by then the policy has already decided, using the
 * declared value. Risk is a property of the capability, not of what the
 * model claims it is about to do with it.
 */
interface NativeTool {

    val name: String
    val description: String

    /** JSON Schema for the arguments; the core validates against it. */
    val inputSchema: String

    /** Must match one of the core's `Risk` values. */
    val risk: String

    val timeoutSeconds: Double get() = 15.0

    val version: String get() = "1"

    /** True when the output is written by third parties, and so taints a run. */
    val returnsUntrustedContent: Boolean get() = false

    /**
     * Called from Python, on whichever thread the run is using. Never
     * the main thread.
     *
     * @param payloadJson `{"arguments": {...}, "run_id": "...", "actor_id": "..."}`
     * @return `{"status": "ok"|"error", "summary": "...", "data": {...}}`
     */
    fun run(payloadJson: String): String

    companion object {
        fun ok(summary: String, data: JSONObject = JSONObject()): String =
            JSONObject()
                .put("status", "ok")
                .put("summary", summary)
                .put("data", data)
                .toString()

        fun error(summary: String, code: String, retryable: Boolean = false): String =
            JSONObject()
                .put("status", "error")
                .put("summary", summary)
                .put("error_code", code)
                .put("retryable", retryable)
                .toString()

        fun argumentsOf(payloadJson: String): JSONObject =
            JSONObject(payloadJson).optJSONObject("arguments") ?: JSONObject()
    }
}
