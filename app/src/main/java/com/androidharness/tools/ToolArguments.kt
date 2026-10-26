package com.androidharness.tools

import org.json.JSONObject

/**
 * The parts of a tool that can be tested without a phone.
 *
 * Every tool here is a thin call onto a platform API wrapped in
 * argument handling, and it is the argument handling that has the bugs:
 * a duration of 0, a vibration longer than the owner's patience, text
 * that would cost a fortune to speak. Keeping that logic pure means CI
 * can check it, which matters because CI is the only machine in this
 * project that can run Kotlin at all.
 */
object ToolArguments {

    /** How long a single vibration may last. Longer is a mistake, not a request. */
    const val MAX_VIBRATION_MS = 5_000L
    const val MIN_VIBRATION_MS = 10L

    /** Roughly five minutes of speech. Past this the owner wants a file. */
    const val MAX_SPOKEN_CHARACTERS = 4_000

    /** What the model is given back from a clipboard read. */
    const val MAX_CLIPBOARD_CHARACTERS = 20_000

    sealed interface Parsed<out T> {
        data class Valid<T>(val value: T) : Parsed<T>
        data class Invalid(val summary: String, val code: String) : Parsed<Nothing>
    }

    fun vibrationMillis(arguments: JSONObject): Parsed<Long> {
        if (!arguments.has("milliseconds")) return Parsed.Valid(400L)
        val raw = arguments.optLong("milliseconds", -1L)
        if (raw <= 0L) {
            return Parsed.Invalid("milliseconds must be a positive whole number", "bad_duration")
        }
        // Clamped rather than refused: the model asking for ten seconds
        // of buzzing is a clumsy request, not an unanswerable one.
        return Parsed.Valid(raw.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS))
    }

    fun spokenText(arguments: JSONObject): Parsed<String> {
        val text = arguments.optString("text").trim()
        if (text.isEmpty()) return Parsed.Invalid("there was nothing to say", "empty_text")
        if (text.length > MAX_SPOKEN_CHARACTERS) {
            return Parsed.Invalid(
                "that is ${text.length} characters; speak at most $MAX_SPOKEN_CHARACTERS " +
                    "at a time, or write it to a file instead",
                "text_too_long",
            )
        }
        return Parsed.Valid(text)
    }

    fun clipboardText(arguments: JSONObject): Parsed<String> {
        if (!arguments.has("text")) return Parsed.Invalid("no text was given", "empty_text")
        val text = arguments.optString("text")
        if (text.isEmpty()) return Parsed.Invalid("no text was given", "empty_text")
        return Parsed.Valid(text)
    }

    /**
     * What to tell the model about text taken off the clipboard.
     *
     * Truncation is announced. Silently shortening what the owner
     * copied would have the model reason confidently about half a
     * document.
     */
    fun describeClipboard(text: String): Pair<String, String> {
        val shown = if (text.length > MAX_CLIPBOARD_CHARACTERS) {
            text.take(MAX_CLIPBOARD_CHARACTERS)
        } else {
            text
        }
        val note = if (text.length > MAX_CLIPBOARD_CHARACTERS) {
            "The clipboard holds ${text.length} characters; " +
                "the first $MAX_CLIPBOARD_CHARACTERS follow.\n\n"
        } else {
            ""
        }
        return shown to note + shown
    }
}
