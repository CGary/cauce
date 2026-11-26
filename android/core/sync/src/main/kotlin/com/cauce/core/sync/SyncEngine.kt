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
    /**
     * Returns the event types brought by this pull, so the app can re-apply device effects.
     * [progress] is filled as the sync advances, so a failure still reports what got through.
     */
    suspend fun sync(progress: SyncProgress = SyncProgress()): Set<String> {
        pushOutbox(progress)
        return pull(progress)
    }

    private suspend fun pushOutbox(progress: SyncProgress) {
        val outbox = store.pending()
        if (outbox.isEmpty()) return

        api.push(outbox)
        store.markSynced(outbox.map { it.id })
        progress.pushed += outbox.size
    }

    private suspend fun pull(progress: SyncProgress): Set<String> {
        val pulledTypes = mutableSetOf<String>()
        var since = cursor.read()

        while (true) {
            val page = api.pull(since)

            if (page.events.isNotEmpty()) {
                progress.pulled += store.appendIfAbsent(page.events)
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

/** Counters of one sync attempt: events pushed and new events pulled (duplicates not counted). */
class SyncProgress {
    var pushed: Int = 0
    var pulled: Int = 0
}
