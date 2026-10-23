package com.cauce.core.events

import com.cauce.core.events.db.CauceDatabase
import com.cauce.core.events.db.EventDao
import com.cauce.core.events.db.EventEntity
import com.cauce.core.events.validation.EventValidator
import com.cauce.core.events.validation.NoopEventValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A local, append-only event log with an outbox flag for sync. */
interface EventStore {
    fun observeAll(): Flow<List<Envelope>>

    fun observeByTypes(types: Set<String>): Flow<List<Envelope>>

    /** Appends locally produced events. Duplicate ids are ignored (idempotency by `id`). */
    suspend fun append(events: List<Envelope>)

    /** Appends pulled events, ignoring ids already present. Returns how many rows were inserted. */
    suspend fun appendIfAbsent(events: List<Envelope>): Int

    suspend fun pending(): List<Envelope>

    suspend fun markSynced(ids: Collection<String>)
}

class InvalidEventException(val eventId: String, val errors: List<String>) :
    IllegalArgumentException("Event $eventId is invalid: ${errors.joinToString("; ")}")

/** Creates the one store the app uses without leaking Room types to the caller. */
object EventStores {
    fun room(
        context: android.content.Context,
        validator: EventValidator = NoopEventValidator,
    ): EventStore = RoomEventStore(CauceDatabase.build(context).eventDao(), validator)
}

internal class RoomEventStore(
    private val dao: EventDao,
    private val validator: EventValidator = NoopEventValidator,
) : EventStore {

    override fun observeAll(): Flow<List<Envelope>> =
        dao.observeAll().map { rows -> rows.map(EventEntity::toEnvelope) }

    override fun observeByTypes(types: Set<String>): Flow<List<Envelope>> =
        if (types.isEmpty()) {
            flowOf(emptyList())
        } else {
            dao.observeByTypes(types.sorted()).map { rows -> rows.map(EventEntity::toEnvelope) }
        }

    override suspend fun append(events: List<Envelope>) {
        validate(events)
        dao.insertIgnore(events.map { EventEntity.fromEnvelope(it) })
    }

    override suspend fun appendIfAbsent(events: List<Envelope>): Int {
        validate(events)
        return dao.insertIgnore(events.map { EventEntity.fromEnvelope(it) }).count { it != -1L }
    }

    override suspend fun pending(): List<Envelope> = dao.pending().map(EventEntity::toEnvelope)

    override suspend fun markSynced(ids: Collection<String>) {
        if (ids.isNotEmpty()) dao.markSynced(ids.toList())
    }

    private fun validate(events: List<Envelope>) {
        events.forEach { envelope ->
            val errors = validator.validate(envelope)
            if (errors.isNotEmpty()) throw InvalidEventException(envelope.id, errors)
        }
    }
}
