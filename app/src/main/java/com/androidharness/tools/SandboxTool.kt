package com.androidharness.tools

import com.androidharness.sandbox.CodeSandbox
import com.androidharness.sandbox.SandboxLimits
import com.androidharness.sandbox.SandboxRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the model write Python and have it run - in Termux, under proot,
 * as a different Android user.
 *
 * This is the capability the whole architecture was arranged around. It
 * is also the only one where the *input* is untrusted: everywhere else
 * the model picks arguments for code we wrote, and here the model
 * writes the code. That is why it does not run in this process.
 *
 * A known gap, recorded rather than glossed: the sandbox has network
 * access. proot hides the filesystem, not the interface. Code with
 * nothing to read can still talk to the outside, so this tool is not a
 * containment story for exfiltration - only for the owner's files.
 */
class SandboxTool(private val sandbox: CodeSandbox) : NativeTool {

    override val name = "run_python"
    override val description =
        "Run a short Python 3 program in an isolated sandbox and return what it " +
            "printed. The program cannot see the owner's files. To produce a file, " +
            "write it in the working directory and name it in expected_outputs."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "code": {"type": "string", "minLength": 1, "maxLength": 20000},
            "expected_outputs": {
              "type": "array",
              "items": {"type": "string", "maxLength": 100},
              "maxItems": 5
            }
          },
          "required": ["code"]
        }
    """.trimIndent()

    // Bounded and isolated, so the owner is not asked to approve every
    // calculation. The isolation is what earns that, not optimism.
    override val risk = "reversible"
    override val timeoutSeconds = 90.0

    override fun run(payloadJson: String): String {
        val arguments = NativeTool.argumentsOf(payloadJson)
        val code = arguments.optString("code").ifBlank {
            return NativeTool.error("no code was supplied", "empty_code")
        }
        val outputs = arguments.optJSONArray("expected_outputs").toStringList()

        val availability = sandbox.availability()
        if (!availability.usable) {
            return NativeTool.error(
                availability.remedy.ifBlank { availability.detail },
                "sandbox_unavailable",
            )
        }

        val outcome = sandbox.run(
            SandboxRequest(script = code, interpreter = "python", expectedOutputs = outputs),
            SandboxLimits(),
        )

        val data = JSONObject()
            .put("stdout", outcome.stdout.take(MAX_TEXT))
            .put("stderr", outcome.stderr.take(MAX_TEXT))
            .put("files", JSONArray(outcome.outputs.keys.toList()))

        if (!outcome.succeeded) {
            return JSONObject()
                .put("status", "error")
                .put(
                    "summary",
                    listOf(outcome.remedy, outcome.stdout.take(MAX_TEXT))
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                )
                .put("error_code", outcome.errorCode.ifBlank { "sandbox_failed" })
                .put("data", data)
                .toString()
        }

        val fileNote = if (outcome.outputs.isEmpty()) {
            ""
        } else {
            " Files produced: " + outcome.outputs.entries.joinToString(", ") {
                "${it.key} (${it.value.size} bytes)"
            }
        }
        return NativeTool.ok(
            (outcome.stdout.take(MAX_TEXT).ifBlank { "The program printed nothing." }) + fileNote,
            data,
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private companion object {
        /** The model pays for every character of this; bound it. */
        const val MAX_TEXT = 4000
    }
}
