package com.androidharness.tools

import com.androidharness.notifications.Note
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turning platform data into something a model can reason about,
 * written as pure functions so CI can check them.
 *
 * The recurring mistake these guard against is handing the model a
 * number without its meaning. A location without an accuracy reads as
 * certainty; a cached fix without its age reads as "now". Both have
 * sent an agent confidently to the wrong place.
 */
object ToolFormatting {

    const val MAX_CONTACTS = 25
    const val MAX_NOTIFICATIONS = 25

    data class Place(
        val latitude: Double,
        val longitude: Double,
        val accuracyMetres: Float,
        val ageSeconds: Long,
        val provider: String,
    )

    fun describeLocation(place: Place): String {
        val latitude = round(place.latitude)
        val longitude = round(place.longitude)
        val accuracy = if (place.accuracyMetres > 0f) {
            ", accurate to about ${place.accuracyMetres.roundToInt()} m"
        } else {
            ""
        }
        return "Latitude $latitude, longitude $longitude$accuracy, " +
            "from ${place.provider}, ${describeAge(place.ageSeconds)}."
    }

    /**
     * How old a fix is, in words.
     *
     * Always stated. A fix from this morning is a perfectly good answer
     * to "where do I usually park" and a useless one to "where am I" -
     * and only the age distinguishes them.
     */
    fun describeAge(seconds: Long): String {
        val age = abs(seconds)
        return when {
            age < 30 -> "just now"
            age < 120 -> "about a minute ago"
            age < 3600 -> "${age / 60} minutes ago"
            age < 7200 -> "about an hour ago"
            age < 86400 -> "${age / 3600} hours ago"
            else -> "${age / 86400} days ago"
        }
    }

    /** Six decimal places is about 11 cm; more is noise pretending to be precision. */
    private fun round(value: Double): Double = (value * 1_000_000).roundToInt() / 1_000_000.0

    data class Person(val name: String, val number: String)

    /**
     * Collapse the duplicates Android's contact store is full of.
     *
     * The same person arrives once per linked account, and the same
     * number arrives written four ways. Comparing digits only is what
     * makes "+91 98765 43210" and "09876543210" one entry.
     */
    fun tidyContacts(people: List<Person>, limit: Int = MAX_CONTACTS): List<Person> {
        val seen = mutableSetOf<String>()
        val tidy = mutableListOf<Person>()
        for (person in people) {
            val name = person.name.trim()
            val number = person.number.trim()
            if (name.isEmpty() && number.isEmpty()) continue
            val fingerprint = name.lowercase() + "|" + number.filter { it.isDigit() }.takeLast(9)
            if (!seen.add(fingerprint)) continue
            tidy += Person(name, number)
            if (tidy.size >= limit) break
        }
        return tidy
    }

    fun describeContacts(people: List<Person>, query: String): String = when {
        people.isEmpty() && query.isBlank() -> "There are no contacts with phone numbers."
        people.isEmpty() -> "No contact matches \"$query\"."
        else -> people.joinToString("\n") { "${it.name}: ${it.number}" }
    }

    /**
     * Notifications, newest first, with the app that sent each one.
     *
     * The app name is not decoration. These are words written by other
     * people, and the model is told to treat them as information rather
     * than instructions - knowing which stranger said what is the whole
     * basis for that judgement.
     */
    fun describeNotifications(notes: List<Note>, nowMillis: Long): String {
        if (notes.isEmpty()) {
            return "There are no recent notifications. (Harness only sees ones that " +
                "arrive while it is running.)"
        }
        return notes.joinToString("\n") { note ->
            val age = describeAge((nowMillis - note.postedAt) / 1000)
            val body = listOf(note.title, note.text).filter { it.isNotBlank() }.joinToString(" - ")
            "[${note.appLabel}, $age] $body"
        }
    }
}
