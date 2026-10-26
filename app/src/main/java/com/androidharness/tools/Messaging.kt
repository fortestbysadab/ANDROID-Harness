package com.androidharness.tools

/**
 * The judgement in batch 3c, kept away from the radio so it can be
 * tested.
 *
 * These two tools are the first that do something the owner cannot
 * undo. A sent message is sent. So the checks here are deliberately
 * strict and deliberately *refusals* rather than repairs: a number the
 * agent is unsure of is a wrong number, and quietly "fixing" one is
 * how a message reaches a stranger.
 */
object Messaging {

    /** A single GSM-7 part; beyond this the message is split and charged per part. */
    const val SINGLE_PART_CHARACTERS = 160

    const val MAX_MESSAGE_CHARACTERS = 1_600

    sealed interface Number {
        data class Dialable(val value: String) : Number
        data class Rejected(val summary: String, val code: String) : Number
    }

    /**
     * Accept a phone number, or refuse it.
     *
     * Spaces, dashes and brackets are how people write numbers, so
     * they are removed. Letters and anything else are not: a number
     * the model partly invented must fail here rather than dial.
     */
    fun number(raw: String): Number {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Number.Rejected("no number was given", "no_number")

        val cleaned = trimmed.filterNot { it in " -()." || it == '\u00A0' }
        val body = cleaned.removePrefix("+")
        if (body.isEmpty() || !body.all { it.isDigit() }) {
            return Number.Rejected(
                "\"$raw\" is not a phone number. Look the contact up first rather " +
                    "than guessing at digits.",
                "bad_number",
            )
        }
        if (body.length < 3) {
            return Number.Rejected("\"$raw\" is too short to be a phone number", "bad_number")
        }
        if (body.length > 15) {
            // E.164 allows fifteen digits at most.
            return Number.Rejected("\"$raw\" has too many digits to be a phone number", "bad_number")
        }
        return Number.Dialable(cleaned)
    }

    sealed interface Message {
        data class Sendable(val text: String, val parts: Int) : Message
        data class Rejected(val summary: String, val code: String) : Message
    }

    fun message(raw: String): Message {
        val text = raw.trim()
        if (text.isEmpty()) return Message.Rejected("there was nothing to send", "empty_message")
        if (text.length > MAX_MESSAGE_CHARACTERS) {
            return Message.Rejected(
                "that message is ${text.length} characters, which would be sent as " +
                    "${partsFor(text.length)} texts; keep it under $MAX_MESSAGE_CHARACTERS",
                "message_too_long",
            )
        }
        return Message.Sendable(text, partsFor(text.length))
    }

    /** Only an estimate - the radio decides - but enough to warn about cost. */
    fun partsFor(length: Int): Int =
        if (length <= SINGLE_PART_CHARACTERS) {
            1
        } else {
            // Concatenated messages spend seven characters per part on
            // the header that stitches them together.
            val perPart = SINGLE_PART_CHARACTERS - 7
            (length + perPart - 1) / perPart
        }

    /**
     * An emergency number cannot be dialled by an app.
     *
     * Android refuses `ACTION_CALL` for these outright. Detecting it
     * here means the owner is told to dial by hand, in the moment it
     * matters, instead of watching the agent fail silently.
     */
    fun isEmergency(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return digits in EMERGENCY
    }

    private val EMERGENCY = setOf(
        "112", "911", "100", "101", "102", "108", "999", "000", "110", "119", "911",
    )
}
