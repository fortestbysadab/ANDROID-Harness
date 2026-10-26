package com.androidharness.tools

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Saying something out loud.
 *
 * Text-to-speech is the one tool in this batch that is genuinely
 * asynchronous twice over: the engine takes a moment to start, and
 * then speaking takes as long as the words do. Both waits are bounded,
 * and the engine is kept once started - rebuilding it per sentence
 * adds a second of silence before every reply.
 */
class SpeakTool(private val context: Context) : NativeTool {

    override val name = "speak"
    override val description =
        "Read text aloud through the phone's speaker. Use this when the owner " +
            "cannot look at the screen; it does not send a message to anyone."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "text": {"type": "string", "minLength": 1, "maxLength": 4000},
            "wait": {"type": "boolean"}
          },
          "required": ["text"]
        }
    """.trimIndent()
    override val risk = "reversible"
    override val timeoutSeconds = 120.0

    override fun run(payloadJson: String): String {
        val arguments = NativeTool.argumentsOf(payloadJson)
        val text = when (val parsed = ToolArguments.spokenText(arguments)) {
            is ToolArguments.Parsed.Invalid -> return NativeTool.error(parsed.summary, parsed.code)
            is ToolArguments.Parsed.Valid -> parsed.value
        }
        // Waiting is the default: a run that ends while the phone is
        // still talking looks, to the owner, like it was cut off.
        val wait = arguments.optBoolean("wait", true)

        val engine = Speech.engine(context)
            ?: return NativeTool.error(
                "The text-to-speech engine would not start. Check that a speech " +
                    "engine is installed under Settings > Accessibility.",
                "tts_unavailable",
            )

        val spoken = Speech.say(engine, text, wait)
        return when (spoken) {
            Speech.Outcome.SPOKEN -> NativeTool.ok(
                "Said it out loud: \"${text.take(120)}\"",
                JSONObject().put("characters", text.length),
            )
            Speech.Outcome.STARTED -> NativeTool.ok(
                "Started speaking; it is still going.",
                JSONObject().put("characters", text.length),
            )
            Speech.Outcome.REFUSED -> NativeTool.error(
                "The speech engine refused the text.",
                "tts_refused",
            )
            Speech.Outcome.TIMED_OUT -> NativeTool.error(
                "The speech engine did not finish in time.",
                "tts_timeout",
                retryable = true,
            )
        }
    }
}

/** The engine itself, started once and kept. */
private object Speech {

    enum class Outcome { SPOKEN, STARTED, REFUSED, TIMED_OUT }

    private var engine: TextToSpeech? = null
    private val utterances = AtomicInteger()

    @Synchronized
    fun engine(context: Context): TextToSpeech? {
        engine?.let { return it }
        val ready = CountDownLatch(1)
        var usable = false
        val built = TextToSpeech(context.applicationContext) { status ->
            usable = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        if (!ready.await(10, TimeUnit.SECONDS) || !usable) {
            built.shutdown()
            return null
        }
        built.language = Locale.getDefault()
        engine = built
        return built
    }

    fun say(engine: TextToSpeech, text: String, wait: Boolean): Outcome {
        val id = "harness-${utterances.incrementAndGet()}"
        val finished = CountDownLatch(1)
        if (wait) {
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) finished.countDown()
                    }

                    @Deprecated("required by the abstract class")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) finished.countDown()
                    }
                },
            )
        }

        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued != TextToSpeech.SUCCESS) return Outcome.REFUSED
        if (!wait) return Outcome.STARTED
        // Generous, because speaking a long paragraph genuinely takes a
        // while; the tool's own timeout is the real ceiling.
        return if (finished.await(100, TimeUnit.SECONDS)) Outcome.SPOKEN else Outcome.TIMED_OUT
    }
}
