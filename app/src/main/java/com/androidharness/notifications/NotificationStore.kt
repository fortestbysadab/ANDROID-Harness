package com.androidharness.notifications

/**
 * What the agent is allowed to know about a notification.
 *
 * A deliberately small subset: who posted it, its title and text, and
 * when. Not the icons, not the actions, not the remote views. The model
 * gets the words and nothing it could be tricked into pressing.
 */
data class Note(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val ongoing: Boolean,
)

/**
 * A bounded, newest-first store of recent notifications.
 *
 * In memory on purpose. Notifications are other people's words arriving
 * continuously; writing them to disk would build a log of the owner's
 * private life that nobody asked for and that outlives the reason it
 * was collected. If the process dies, the history goes with it, which
 * is the correct trade.
 */
class NotificationStore(private val capacity: Int = 50) {

    private val notes = ArrayDeque<Note>()

    @Synchronized
    fun remember(note: Note) {
        // Updating a notification re-posts it under the same key; the
        // owner thinks of that as one notification, not two.
        notes.removeAll { it.key == note.key }
        notes.addFirst(note)
        while (notes.size > capacity) notes.removeLast()
    }

    @Synchronized
    fun forget(key: String) {
        notes.removeAll { it.key == key }
    }

    @Synchronized
    fun clear() = notes.clear()

    @Synchronized
    fun recent(limit: Int = 20, includeOngoing: Boolean = false): List<Note> =
        notes.asSequence()
            // Ongoing notifications are music players and downloads -
            // always present, never news. They drown out the rest.
            .filter { includeOngoing || !it.ongoing }
            .take(limit.coerceIn(1, capacity))
            .toList()

    @Synchronized
    fun size(): Int = notes.size

    companion object {
        /** Shared, because the listener service and the tool are separate objects. */
        val shared = NotificationStore()
    }
}
