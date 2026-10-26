package com.androidharness.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification history.
 *
 * Plain Kotlin with no Android in it, which is why it can be tested at
 * all - and the reason it was written that way. The listener service
 * cannot be unit tested; this, where the decisions are, can.
 */
class NotificationStoreTest {

    private fun note(
        key: String,
        title: String = "t",
        ongoing: Boolean = false,
        posted: Long = 0,
    ) = Note(key, "com.example", "Example", title, "body", posted, ongoing)

    @Test
    fun `the newest notification comes first`() {
        val store = NotificationStore()
        store.remember(note("a"))
        store.remember(note("b"))
        assertEquals(listOf("b", "a"), store.recent().map { it.key })
    }

    @Test
    fun `an updated notification is one notification, not two`() {
        // Android re-posts under the same key when a message thread
        // gains a message. The owner thinks of that as one thing.
        val store = NotificationStore()
        store.remember(note("chat", title = "1 message"))
        store.remember(note("chat", title = "2 messages"))
        assertEquals(1, store.size())
        assertEquals("2 messages", store.recent().single().title)
    }

    @Test
    fun `an updated notification moves back to the top`() {
        val store = NotificationStore()
        store.remember(note("a"))
        store.remember(note("b"))
        store.remember(note("a", title = "updated"))
        assertEquals(listOf("a", "b"), store.recent().map { it.key })
    }

    @Test
    fun `dismissing a notification forgets it`() {
        val store = NotificationStore()
        store.remember(note("a"))
        store.forget("a")
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `forgetting something that was never there is harmless`() {
        val store = NotificationStore()
        store.remember(note("a"))
        store.forget("ghost")
        assertEquals(1, store.size())
    }

    @Test
    fun `the store is bounded`() {
        // It is in memory and fed by every app on the phone; unbounded
        // would be a slow leak.
        val store = NotificationStore(capacity = 3)
        repeat(10) { store.remember(note("k$it")) }
        assertEquals(3, store.size())
        assertEquals(listOf("k9", "k8", "k7"), store.recent().map { it.key })
    }

    @Test
    fun `ongoing notifications are hidden by default`() {
        // Music players and downloads are always present and never
        // news; they would crowd out everything that is.
        val store = NotificationStore()
        store.remember(note("music", ongoing = true))
        store.remember(note("message"))
        assertEquals(listOf("message"), store.recent().map { it.key })
    }

    @Test
    fun `ongoing notifications can be asked for`() {
        val store = NotificationStore()
        store.remember(note("music", ongoing = true))
        assertEquals(1, store.recent(includeOngoing = true).size)
    }

    @Test
    fun `a limit is respected`() {
        val store = NotificationStore()
        repeat(10) { store.remember(note("k$it")) }
        assertEquals(4, store.recent(limit = 4).size)
    }

    @Test
    fun `an absurd limit cannot exceed what is held`() {
        val store = NotificationStore(capacity = 5)
        repeat(5) { store.remember(note("k$it")) }
        assertEquals(5, store.recent(limit = 10_000).size)
    }

    @Test
    fun `disconnecting clears the history`() {
        // After a rebind the list would be stale, and stale
        // notifications read exactly like current ones.
        val store = NotificationStore()
        store.remember(note("a"))
        store.clear()
        assertEquals(0, store.size())
    }
}
