package com.cauce.core.events.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import kotlinx.serialization.json.jsonObject

/**
 * One row per event. [synced] is the outbox flag: `false` means the event still has to reach the
 * server. Rows are never updated except to flip [synced]; the log itself is append-only.
 */
@Entity(
    tableName = "events",
    indices = [Index("type"), Index("synced")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val type: String,
    val v: Int,
    val ts: String,
    val payload: String,
    val synced: Boolean,
) {
    fun toEnvelope(): Envelope = Envelope(
        id = id,
        userId = userId,
        deviceId = deviceId,
        type = type,
        v = v,
        ts = ts,
        payload = CauceJson.wire.parseToJsonElement(payload).jsonObject,
    )

    companion object {
        fun fromEnvelope(envelope: Envelope, synced: Boolean = false): EventEntity = EventEntity(
            id = envelope.id,
            userId = envelope.userId,
            deviceId = envelope.deviceId,
            type = envelope.type,
            v = envelope.v,
            ts = envelope.ts,
            payload = envelope.payload.toString(),
            synced = synced,
        )
    }
}
