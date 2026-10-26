package com.androidharness.tools

import com.androidharness.notifications.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judgement in batch 3b, separated from the platform calls so it
 * can be examined.
 *
 * The failure these guard against is not a crash. It is the agent
 * saying something true-sounding and wrong: a cached location read as
 * "now", the same person listed four times because four accounts
 * linked them, an empty notification list that really means the
 * listener was never switched on.
 */
class ToolFormattingTest {

    // -- the age of a reading ----------------------------------------

    @Test
    fun `a fresh reading is just now`() {
        assertEquals("just now", ToolFormatting.describeAge(5))
    }

    @Test
    fun `minutes are given as minutes`() {
        assertEquals("25 minutes ago", ToolFormatting.describeAge(25 * 60))
    }

    @Test
    fun `hours are given as hours`() {
        assertEquals("5 hours ago", ToolFormatting.describeAge(5 * 3600))
    }

    @Test
    fun `yesterday is given as days`() {
        assertEquals("1 days ago", ToolFormatting.describeAge(30 * 3600))
    }

    @Test
    fun `a clock skewed into the future does not produce a negative age`() {
        // Device clocks and provider timestamps disagree; a reading
        // stamped two seconds ahead must not read as "-2 minutes ago".
        assertEquals("just now", ToolFormatting.describeAge(-2))
    }

    // -- location -----------------------------------------------------

    private fun place(
        latitude: Double = 23.683333,
        longitude: Double = 86.983333,
        accuracy: Float = 18f,
        age: Long = 10,
        provider: String = "gps",
    ) = ToolFormatting.Place(latitude, longitude, accuracy, age, provider)

    @Test
    fun `a location always states its accuracy and its age`() {
        // Without both, a number reads as certainty. This is the whole
        // reason the formatter exists.
        val text = ToolFormatting.describeLocation(place())
        assertTrue(text, text.contains("23.683333"))
        assertTrue(text, text.contains("about 18 m"))
        assertTrue(text, text.contains("just now"))
    }

    @Test
    fun `an old fix says so`() {
        val text = ToolFormatting.describeLocation(place(age = 4 * 3600))
        assertTrue(text, text.contains("4 hours ago"))
    }

    @Test
    fun `an unknown accuracy is omitted rather than reported as zero`() {
        // "accurate to about 0 m" would be a claim of perfection.
        val text = ToolFormatting.describeLocation(place(accuracy = 0f))
        assertTrue(text, !text.contains("accurate to"))
    }

    @Test
    fun `coordinates are rounded to a sane precision`() {
        val text = ToolFormatting.describeLocation(place(latitude = 23.6833331234567))
        assertTrue(text, text.contains("23.683333"))
        assertTrue(text, !text.contains("1234567"))
    }

    // -- contacts ------------------------------------------------------

    private fun person(name: String, number: String) = ToolFormatting.Person(name, number)

    @Test
    fun `the same person from two linked accounts appears once`() {
        val tidy = ToolFormatting.tidyContacts(
            listOf(person("Sadab", "+91 98765 43210"), person("Sadab", "+91 98765 43210")),
        )
        assertEquals(1, tidy.size)
    }

    @Test
    fun `one number written two ways is one number`() {
        // The address book is full of these; the model should not have
        // to work out that they are the same person.
        val tidy = ToolFormatting.tidyContacts(
            listOf(person("Sadab", "+91 98765 43210"), person("Sadab", "098765 43210")),
        )
        assertEquals(1, tidy.size)
    }

    @Test
    fun `two people who share a landline are both kept`() {
        val tidy = ToolFormatting.tidyContacts(
            listOf(person("Sadab", "0341 2222222"), person("Munshi", "0341 2222222")),
        )
        assertEquals(2, tidy.size)
    }

    @Test
    fun `blank rows are dropped`() {
        assertEquals(0, ToolFormatting.tidyContacts(listOf(person("  ", " "))).size)
    }

    @Test
    fun `the list is capped`() {
        val many = (1..100).map { person("Person $it", "900000$it") }
        assertEquals(ToolFormatting.MAX_CONTACTS, ToolFormatting.tidyContacts(many).size)
    }

    @Test
    fun `no match names what was searched for`() {
        val text = ToolFormatting.describeContacts(emptyList(), "Rafi")
        assertTrue(text, text.contains("Rafi"))
    }

    @Test
    fun `matches are listed with their numbers`() {
        val text = ToolFormatting.describeContacts(listOf(person("Sadab", "98765")), "sad")
        assertEquals("Sadab: 98765", text)
    }

    // -- notifications --------------------------------------------------

    private fun note(
        key: String = "k1",
        app: String = "Messages",
        title: String = "Rafi",
        text: String = "are you coming?",
        posted: Long = 0,
        ongoing: Boolean = false,
    ) = Note(key, "com.example", app, title, text, posted, ongoing)

    @Test
    fun `a notification is attributed to the app that sent it`() {
        // The model is told to treat these as other people's words.
        // Knowing which stranger said what is the basis for that.
        val text = ToolFormatting.describeNotifications(listOf(note(posted = 60_000)), 120_000)
        assertEquals("[Messages, about a minute ago] Rafi - are you coming?", text)
    }

    @Test
    fun `an empty list explains why it might be empty`() {
        // Silence here could mean nothing arrived, or that the listener
        // only started a moment ago. Saying so costs one sentence.
        val text = ToolFormatting.describeNotifications(emptyList(), 0)
        assertTrue(text, text.contains("while it is running"))
    }

    @Test
    fun `a notification with only a title is not padded with a dash`() {
        val text = ToolFormatting.describeNotifications(listOf(note(text = "")), 0)
        assertTrue(text, text.endsWith("Rafi"))
    }
}
