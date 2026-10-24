package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.jsonPrimitive

/**
 * SPEC 2.5: an occurrence may be postponed up to 3 times; the fourth "Posponer" marks it
 * `ignorada`. Pure over the log so the receiver stays thin.
 */
internal sealed interface SnoozeOutcome {
    data class Pospuesta(val n: Int) : SnoozeOutcome
    data object Ignorada : SnoozeOutcome
}

internal fun nextSnoozeOutcome(
    events: List<Envelope>,
    alarmaId: String,
    programadaPara: String,
): SnoozeOutcome {
    val pospuestas = events.count { event ->
        event.type == "alarma_atendida" &&
            event.payload.getValue("alarma_id").jsonPrimitive.content == alarmaId &&
            event.payload.getValue("programada_para").jsonPrimitive.content == programadaPara &&
            event.payload.getValue("accion").jsonPrimitive.content == "pospuesta"
    }
    return if (pospuestas >= 3) SnoozeOutcome.Ignorada else SnoozeOutcome.Pospuesta(pospuestas + 1)
}

/** SPEC 2.3/2.5: only these close a firing; `pospuesta` keeps it open for the snooze re-fire. */
internal val TERMINAL_ACTIONS = setOf("hecho", "ignorada")

/** Whether the occurrence is already closed by a terminal `alarma_atendida` (2-minute timeout). */
internal fun hasAtendida(events: List<Envelope>, alarmaId: String, programadaPara: String): Boolean =
    events.any { event ->
        event.type == "alarma_atendida" &&
            event.payload.getValue("alarma_id").jsonPrimitive.content == alarmaId &&
            event.payload.getValue("programada_para").jsonPrimitive.content == programadaPara &&
            event.payload.getValue("accion").jsonPrimitive.content in TERMINAL_ACTIONS
    }
