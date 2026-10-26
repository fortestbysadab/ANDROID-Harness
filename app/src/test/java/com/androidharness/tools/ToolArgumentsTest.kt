package com.androidharness.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The part of a native tool that can be wrong without a phone.
 *
 * Each of these tools is a one-line call onto a platform API wrapped in
 * argument handling, and the argument handling is where the bugs live:
 * a duration of zero, a buzz longer than anyone wants, a clipboard read
 * quietly cut in half. The platform call itself either works on the
 * device or does not, and no test here can tell us which.
 *
 * These run on the JVM, which matters: CI is the only machine in this
 * project that compiles Kotlin at all.
 */
class ToolArgumentsTest {

    private fun arguments(json: String) = JSONObject(json)

    private fun <T> valid(parsed: ToolArguments.Parsed<T>): T {
        assertTrue("expected valid, got $parsed", parsed is ToolArguments.Parsed.Valid)
        return (parsed as ToolArguments.Parsed.Valid).value
    }

    private fun invalid(parsed: ToolArguments.Parsed<*>): ToolArguments.Parsed.Invalid {
        assertTrue("expected invalid, got $parsed", parsed is ToolArguments.Parsed.Invalid)
        return parsed as ToolArguments.Parsed.Invalid
    }

    // -- vibration --------------------------------------------------

    @Test
    fun `a vibration with no duration gets a sensible one`() {
        assertEquals(400L, valid(ToolArguments.vibrationMillis(arguments("{}"))))
    }

    @Test
    fun `a requested duration is honoured`() {
        val millis = valid(ToolArguments.vibrationMillis(arguments("""{"milliseconds": 750}""")))
        assertEquals(750L, millis)
    }

    @Test
    fun `an absurd duration is clamped rather than refused`() {
        // The model asking for a minute of buzzing is clumsy, not
        // unanswerable. Refusing would make it retry; clamping does
        // what was meant.
        val millis = valid(ToolArguments.vibrationMillis(arguments("""{"milliseconds": 60000}""")))
        assertEquals(ToolArguments.MAX_VIBRATION_MS, millis)
    }

    @Test
    fun `a duration too short to feel is raised to the floor`() {
        val millis = valid(ToolArguments.vibrationMillis(arguments("""{"milliseconds": 1}""")))
        assertEquals(ToolArguments.MIN_VIBRATION_MS, millis)
    }

    @Test
    fun `zero is a mistake, not a request for nothing`() {
        assertEquals("bad_duration", invalid(ToolArguments.vibrationMillis(arguments("""{"milliseconds": 0}"""))).code)
    }

    @Test
    fun `a negative duration is refused`() {
        assertEquals("bad_duration", invalid(ToolArguments.vibrationMillis(arguments("""{"milliseconds": -5}"""))).code)
    }

    @Test
    fun `a duration that is not a number is refused rather than read as zero`() {
        assertEquals("bad_duration", invalid(ToolArguments.vibrationMillis(arguments("""{"milliseconds": "soon"}"""))).code)
    }

    // -- speech -----------------------------------------------------

    @Test
    fun `text to speak is passed through`() {
        assertEquals("hello", valid(ToolArguments.spokenText(arguments("""{"text": "hello"}"""))))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("hello", valid(ToolArguments.spokenText(arguments("""{"text": "  hello \n"}"""))))
    }

    @Test
    fun `whitespace alone is nothing to say`() {
        assertEquals("empty_text", invalid(ToolArguments.spokenText(arguments("""{"text": "   "}"""))).code)
    }

    @Test
    fun `missing text is nothing to say`() {
        assertEquals("empty_text", invalid(ToolArguments.spokenText(arguments("{}"))).code)
    }

    @Test
    fun `an essay is refused, and the refusal says what to do instead`() {
        val text = "a".repeat(ToolArguments.MAX_SPOKEN_CHARACTERS + 1)
        val refusal = invalid(ToolArguments.spokenText(JSONObject().put("text", text)))
        assertEquals("text_too_long", refusal.code)
        assertTrue("the remedy must be actionable", refusal.summary.contains("file"))
    }

    @Test
    fun `text at exactly the limit is allowed`() {
        val text = "a".repeat(ToolArguments.MAX_SPOKEN_CHARACTERS)
        assertEquals(text.length, valid(ToolArguments.spokenText(JSONObject().put("text", text))).length)
    }

    // -- clipboard writes -------------------------------------------

    @Test
    fun `clipboard text is taken verbatim, whitespace and all`() {
        // Unlike speech, this is data the owner will paste somewhere.
        // Trimming it would corrupt it.
        assertEquals("  spaced  ", valid(ToolArguments.clipboardText(arguments("""{"text": "  spaced  "}"""))))
    }

    @Test
    fun `an empty string is not something to copy`() {
        assertEquals("empty_text", invalid(ToolArguments.clipboardText(arguments("""{"text": ""}"""))).code)
    }

    @Test
    fun `missing text is not something to copy`() {
        assertEquals("empty_text", invalid(ToolArguments.clipboardText(arguments("{}"))).code)
    }

    // -- clipboard reads --------------------------------------------

    @Test
    fun `a short clipboard is reported whole and unannotated`() {
        val (shown, summary) = ToolArguments.describeClipboard("hello")
        assertEquals("hello", shown)
        assertEquals("hello", summary)
    }

    @Test
    fun `a long clipboard is cut, and the cut is announced`() {
        // Silently halving a document would have the model reason
        // confidently about the part it cannot see.
        val text = "a".repeat(ToolArguments.MAX_CLIPBOARD_CHARACTERS + 500)
        val (shown, summary) = ToolArguments.describeClipboard(text)
        assertEquals(ToolArguments.MAX_CLIPBOARD_CHARACTERS, shown.length)
        assertTrue(summary.startsWith("The clipboard holds ${text.length} characters"))
        assertTrue(summary.endsWith(shown))
    }

    @Test
    fun `a clipboard at exactly the limit is not announced as cut`() {
        val text = "a".repeat(ToolArguments.MAX_CLIPBOARD_CHARACTERS)
        val (_, summary) = ToolArguments.describeClipboard(text)
        assertEquals(text, summary)
    }
}
