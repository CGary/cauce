package com.cauce.core.sync

import com.cauce.core.events.EventStore

/**
 * Push the outbox, then pull pages until the server has nothing new. Pulled events are inserted
 * with dedupe by `id`, so replaying a page is harmless.
 */
class SyncEngine(
    private val store: EventStore,
    private val api: SyncApi,
    private val cursor: SyncCursorStore,
) {
    /** Returns the event types brought by this pull, so the app can re-apply device effects. */
    suspend fun sync(): Set<String> {
        pushOutbox()
        return pull()
    }

    private suspend fun pushOutbox() {
        val outbox = store.pending()
        if (outbox.isEmpty()) return

        api.push(outbox)
        store.markSynced(outbox.map { it.id })
    }

    private suspend fun pull(): Set<String> {
        val pulledTypes = mutableSetOf<String>()
        var since = cursor.read()

        while (true) {
            val page = api.pull(since)

            if (page.events.isNotEmpty()) {
                store.appendIfAbsent(page.events)
                page.events.mapTo(pulledTypes) { it.type }
            }

            if (page.events.isEmpty() || page.nextSince <= since) break
            since = page.nextSince
            // A crash between the append above and this write is harmless: the next pull replays
            // the page and appendIfAbsent dedupes by id.
            cursor.write(since)
        }

        return pulledTypes
    }
}
