package com.androidharness.tools

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import org.json.JSONObject

/**
 * Reading the clipboard.
 *
 * Since Android 10 this only works when the app holds input focus or is
 * the default keyboard. The system does not throw when it refuses - it
 * returns an empty clipboard, which is the trap: an agent that says
 * "your clipboard is empty" when it was actually denied has told the
 * owner something false with complete confidence. So a null clip is
 * reported as a denial with a remedy, and only a clip that exists and
 * is empty is reported as empty.
 *
 * Whatever is on the clipboard was not necessarily written by the
 * owner, so this taints the run.
 */
class GetClipboardTool(private val context: Context) : NativeTool {

    override val name = "get_clipboard"
    override val description =
        "Read the text currently on the clipboard. Only works while the app is " +
            "on screen, because Android forbids background clipboard reads."
    override val inputSchema = """{"type":"object","properties":{},"required":[]}"""
    override val risk = "sensitive_read"
    override val returnsUntrustedContent = true

    override fun run(payloadJson: String): String {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return NativeTool.error("no clipboard service", "clipboard_unavailable")

        val clip = MainThread.get { runCatching { manager.primaryClip }.getOrNull() }
            ?: return NativeTool.error(
                "Android refused the read. An app can only read the clipboard while " +
                    "it is on screen - open Harness and ask again. (If the clipboard " +
                    "really is empty, this looks the same; Android does not " +
                    "distinguish them.)",
                "clipboard_read_denied",
            )

        if (clip.itemCount == 0) return NativeTool.ok("The clipboard is empty.")
        val text = (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (text.isEmpty()) {
            return NativeTool.ok("The clipboard holds something that is not text.")
        }

        val (shown, summary) = ToolArguments.describeClipboard(text)
        return NativeTool.ok(
            summary,
            JSONObject().put("text", shown).put("length", text.length),
        )
    }
}

/**
 * Writing the clipboard.
 *
 * Writes are not gated the way reads are, so this works from a
 * scheduled run. Android 13 shows everything copied in an on-screen
 * preview, so a caller may mark the clip sensitive and keep a password
 * off the screen.
 */
class SetClipboardTool(private val context: Context) : NativeTool {

    override val name = "set_clipboard"
    override val description =
        "Put text on the clipboard so the owner can paste it. Set sensitive to " +
            "true for passwords or card numbers, to keep them out of the " +
            "on-screen clipboard preview."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "text": {"type": "string", "maxLength": 100000},
            "sensitive": {"type": "boolean"}
          },
          "required": ["text"]
        }
    """.trimIndent()
    override val risk = "reversible"

    override fun run(payloadJson: String): String {
        val arguments = NativeTool.argumentsOf(payloadJson)
        val text = when (val parsed = ToolArguments.clipboardText(arguments)) {
            is ToolArguments.Parsed.Invalid -> return NativeTool.error(parsed.summary, parsed.code)
            is ToolArguments.Parsed.Valid -> parsed.value
        }
        val sensitive = arguments.optBoolean("sensitive", false)

        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return NativeTool.error("no clipboard service", "clipboard_unavailable")

        val done = MainThread.get {
            val clip = ClipData.newPlainText("Harness", text)
            if (sensitive) {
                // EXTRA_IS_SENSITIVE arrived in API 33, but it is a
                // compile-time string constant, so writing it on an
                // older release is harmless and some OEM previews
                // honour it anyway.
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            manager.setPrimaryClip(clip)
            true
        } ?: return NativeTool.error("the clipboard did not respond", "clipboard_timeout")

        val note = if (sensitive) " It is marked sensitive, so it will not be previewed." else ""
        return NativeTool.ok(
            "Copied ${text.length} characters to the clipboard.$note",
            JSONObject().put("length", text.length).put("sensitive", sensitive),
        )
    }
}
