package com.cauce.core.sync

import com.cauce.core.events.Envelope
import com.cauce.core.events.EventStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncEngineTest {
    @Test
    fun `pull continues past a short page until the server returns an empty page`() = runBlocking {
        val store = FakeStore()
        val cursor = FakeCursor(10)
        val api = FakeApi(
            listOf(
                PullResponse(List(500) { event("p1-$it", "alarma_creada") }, nextSince = 510),
                PullResponse(List(3) { event("p2-$it", "alarma_modificada") }, nextSince = 513),
                PullResponse(listOf(event("p3-0", "alarma_disparada")), nextSince = 514),
                PullResponse(emptyList(), nextSince = 514),
            ),
        )

        val pulled = SyncEngine(store, api, cursor).sync()

        assertEquals(4, api.calls)
        assertEquals(504, store.appended.size)
        assertEquals(514L, cursor.value)
        assertTrue("alarma_disparada" in pulled)
    }

    @Test
    fun `pull stops when next_since does not advance`() = runBlocking {
        val store = FakeStore()
        val cursor = FakeCursor(10)
        val api = FakeApi(
            listOf(
                PullResponse(listOf(event("p1-0", "alarma_creada")), nextSince = 10),
            ),
        )

        val pulled = SyncEngine(store, api, cursor).sync()

        assertEquals(1, api.calls)
        assertEquals(1, store.appended.size)
        assertEquals(10L, cursor.value)
        assertTrue("alarma_creada" in pulled)
    }

    private class FakeStore : EventStore {
        val appended = mutableListOf<Envelope>()

        override fun observeAll(): Flow<List<Envelope>> = flowOf(emptyList())

        override fun observeByTypes(types: Set<String>): Flow<List<Envelope>> = flowOf(emptyList())

        override suspend fun append(events: List<Envelope>) {
            appended += events
        }

        override suspend fun appendIfAbsent(events: List<Envelope>): Int {
            val known = appended.mapTo(mutableSetOf()) { it.id }
            val fresh = events.filter { known.add(it.id) }
            appended += fresh
            return fresh.size
        }

        override suspend fun pending(): List<Envelope> = emptyList()

        override suspend fun markSynced(ids: Collection<String>) = Unit
    }

    private class FakeCursor(var value: Long) : SyncCursorStore {
        override suspend fun read(): Long = value

        override suspend fun write(value: Long) {
            this.value = value
        }
    }

    private class FakeApi(private val pages: List<PullResponse>) : SyncApi {
        var calls = 0

        override suspend fun push(events: List<Envelope>): PushResponse = PushResponse()

        override suspend fun pull(since: Long): PullResponse = pages[calls++]

        override suspend fun presign(key: String, contentType: String): PresignResponse =
            PresignResponse()
    }

    private fun event(id: String, type: String): Envelope = Envelope(
        id = id,
        userId = "u1",
        deviceId = "test",
        type = type,
        v = 1,
        ts = "2026-10-22T10:00:00Z",
        payload = JsonObject(emptyMap()),
    )
}
