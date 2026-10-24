package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * SPEC §8 (agente): a snoozed occurrence keeps ringing even when its calendar already emitted
 * `alarma_desactivada(fin_tratamiento)` for that last firing. Only a deactivation by the user
 * (`motivo: usuario`) cancels the snooze. Pure over the log so the receiver stays thin.
 */
internal fun shouldRing(events: List<Envelope>, alarmaId: String, snoozed: Boolean): Boolean {
    val alarm = foldAlarms(events).firstOrNull { it.id == alarmaId } ?: return false
    if (!alarm.deactivated) return true
    return snoozed && lastDeactivationMotivo(events, alarmaId) == "fin_tratamiento"
}

/** Motivo of the latest `alarma_desactivada` for this alarm, or null when there is none. */
internal fun lastDeactivationMotivo(events: List<Envelope>, alarmaId: String): String? {
    var latest: Instant? = null
    var motivo: String? = null
    events.forEach { event ->
        if (event.type != "alarma_desactivada") return@forEach
        if (event.payload.getValue("alarma_id").jsonPrimitive.content != alarmaId) return@forEach
        val ts = Instant.parse(event.ts)
        val previous = latest
        if (previous == null || !ts.isBefore(previous)) {
            latest = ts
            motivo = event.payload.getValue("motivo").jsonPrimitive.content
        }
    }
    return motivo
}
