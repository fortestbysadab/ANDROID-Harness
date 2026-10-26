package com.androidharness.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks in front of the two irreversible tools.
 *
 * Everything else in the app can be undone, retried, or ignored. A sent
 * message is sent, and a call that connects has already been answered
 * by a person. So these tests are mostly about *refusing*: the
 * recurring failure of an agent with a phone is not that it cannot
 * dial, it is that it dials something it half-invented.
 */
class MessagingTest {

    private fun dialable(raw: String): String {
        val parsed = Messaging.number(raw)
        assertTrue("expected $raw to be accepted, got $parsed", parsed is Messaging.Number.Dialable)
        return (parsed as Messaging.Number.Dialable).value
    }

    private fun rejected(raw: String): Messaging.Number.Rejected {
        val parsed = Messaging.number(raw)
        assertTrue("expected $raw to be refused, got $parsed", parsed is Messaging.Number.Rejected)
        return parsed as Messaging.Number.Rejected
    }

    // -- numbers people actually write -------------------------------

    @Test
    fun `a plain number is accepted`() {
        assertEquals("9876543210", dialable("9876543210"))
    }

    @Test
    fun `an international number keeps its plus`() {
        // Dropping it would silently turn an international number into
        // a local one, which is a different phone.
        assertEquals("+919876543210", dialable("+91 98765 43210"))
    }

    @Test
    fun `spaces, dashes and brackets are how people write numbers`() {
        assertEquals("03412222222", dialable("(0341) 222-2222"))
    }

    @Test
    fun `a trunk zero is kept, because removing it would be a repair`() {
        // Tempting to strip: "+91 (0) 98765 43210" is not a valid
        // international number with the zero in it. But deciding which
        // zero is a trunk prefix is a guess about dialling plans, and a
        // guess here rings somebody. The digits are passed through as
        // written and the network can refuse them.
        assertEquals("+91098765432", dialable("+91 (0) 98765 432"))
    }

    @Test
    fun `a non-breaking space from a copied contact is handled`() {
        assertEquals("+919876543210", dialable("+91\u00A098765\u00A043210"))
    }

    // -- numbers that must not be dialled ----------------------------

    @Test
    fun `a number with letters in it is refused, not cleaned`() {
        // "Call 1-800-FLOWERS" is a wrong number as far as this app is
        // concerned, and guessing at the digits is how a stranger's
        // phone rings.
        assertEquals("bad_number", rejected("1-800-FLOWERS").code)
    }

    @Test
    fun `the refusal tells the model what to do instead`() {
        assertTrue(rejected("Rafi").summary.contains("Look the contact up"))
    }

    @Test
    fun `an empty number is refused`() {
        assertEquals("no_number", rejected("   ").code)
    }

    @Test
    fun `something far too short is refused`() {
        assertEquals("bad_number", rejected("12").code)
    }

    @Test
    fun `more digits than E164 allows is refused`() {
        assertEquals("bad_number", rejected("1234567890123456").code)
    }

    @Test
    fun `a plus on its own is refused`() {
        assertEquals("bad_number", rejected("+").code)
    }

    @Test
    fun `a number with a plus in the middle is refused`() {
        assertEquals("bad_number", rejected("98765+43210").code)
    }

    // -- emergency numbers -------------------------------------------

    @Test
    fun `emergency numbers are recognised`() {
        assertTrue(Messaging.isEmergency("112"))
        assertTrue(Messaging.isEmergency("911"))
        assertTrue(Messaging.isEmergency("100"))
    }

    @Test
    fun `an ordinary number is not an emergency number`() {
        assertTrue(!Messaging.isEmergency("9876543210"))
    }

    @Test
    fun `an emergency number written with spacing is still recognised`() {
        assertTrue(Messaging.isEmergency("1 1 2"))
    }

    // -- messages -----------------------------------------------------

    private fun sendable(raw: String): Messaging.Message.Sendable {
        val parsed = Messaging.message(raw)
        assertTrue("expected sendable, got $parsed", parsed is Messaging.Message.Sendable)
        return parsed as Messaging.Message.Sendable
    }

    @Test
    fun `a short message is one part`() {
        assertEquals(1, sendable("running late").parts)
    }

    @Test
    fun `a message at the single-part limit is still one part`() {
        assertEquals(1, sendable("a".repeat(160)).parts)
    }

    @Test
    fun `one character more becomes two parts`() {
        // Worth knowing about: it is two messages and two charges.
        assertEquals(2, sendable("a".repeat(161)).parts)
    }

    @Test
    fun `an empty message is refused`() {
        val parsed = Messaging.message("   ")
        assertTrue(parsed is Messaging.Message.Rejected)
        assertEquals("empty_message", (parsed as Messaging.Message.Rejected).code)
    }

    @Test
    fun `an enormous message is refused with its cost spelled out`() {
        val parsed = Messaging.message("a".repeat(2000))
        assertTrue(parsed is Messaging.Message.Rejected)
        val refusal = parsed as Messaging.Message.Rejected
        assertEquals("message_too_long", refusal.code)
        assertTrue(refusal.summary, refusal.summary.contains("texts"))
    }

    @Test
    fun `the part count accounts for the concatenation header`() {
        // Multipart messages spend seven characters per part stitching
        // themselves together, so 320 characters is three texts.
        assertEquals(3, Messaging.partsFor(320))
    }
}
