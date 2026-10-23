package com.cauce.core.events.projection

import com.cauce.core.events.Envelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * A pure view over the event log: `(events, params) -> T`.
 *
 * Implementations must not read the clock, do I/O or use randomness. Time arrives as `params.now`.
 * The registry filters [events] to [eventTypes] before calling [compute], so an implementation can
 * assume every event it sees is relevant.
 */
interface Projection<T> {
    /** `<feature>.<name>`, e.g. `alarmas.alarmas_activas`. This is the fixture key. */
    val key: String

    val eventTypes: Set<String>

    /** Serializes [T] to JSON with [com.cauce.core.events.CauceJson.output]. */
    val serializer: KSerializer<T>

    fun compute(events: List<Envelope>, params: JsonObject): T
}
