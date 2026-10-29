package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Pure parts of the Android v5 surface: `gcal:` entries (SPEC 2.10) and the editor pre-fill (2.1). */

private const val GCAL_PREFIX = "gcal:"

/** Read-only entries derived from `calendario_google` events: no toggle, no editor, no state fold. */
internal fun isGcalId(alarmaId: String): Boolean = alarmaId.startsWith(GCAL_PREFIX)

/**
 * Looks up the P1 row of a `gcal:` entry at firing time. P1 only lists avisos strictly after
 * `now`, so the lookup runs one millisecond before the scheduled instant. Null when the meeting
 * is gone (deleted, rejected, aviso edited elsewhere).
 */
internal fun gcalRow(
    events: List<Envelope>,
    alarmaId: String,
    programadaPara: Instant,
    zone: ZoneId,
): AlarmaActiva? =
    googleAlarmas(events, programadaPara.minusMillis(1), zone).firstOrNull { it.alarmaId == alarmaId }

/** A ringable [Alarm] for a `gcal:` entry: title/tipo come from the P1 row, never from the state fold. */
internal fun gcalAlarm(
    events: List<Envelope>,
    alarmaId: String,
    programadaPara: Instant,
    zone: ZoneId,
): Alarm {
    val row = gcalRow(events, alarmaId, programadaPara, zone)
    val hora = row?.hora ?: formatLocalHora(programadaPara, zone)
    return Alarm(
        id = alarmaId,
        createdAt = programadaPara,
        titulo = row?.titulo ?: "Reunión de Google Calendar",
        tipo = "reunion",
        hora = LocalTime.parse(hora),
        horaRaw = hora,
        repeticion = JsonNull,
        rule = null,
        destinatario = null,
        remedio = null,
        fecha = null,
        deactivated = false,
    )
}

/** Whether a firing rings: gcal entries ring when their row still exists (or when snoozed). */
internal fun shouldRingGcal(
    events: List<Envelope>,
    alarmaId: String,
    programadaPara: Instant,
    zone: ZoneId,
    snoozed: Boolean,
): Boolean = snoozed || gcalRow(events, alarmaId, programadaPara, zone) != null

/**
 * Events appended when an alarm fires. Always `alarma_disparada`; plus `alarma_desactivada`
 * (`fin_tratamiento`) when it is not a snooze and the calendar has no later firing. `gcal:`
 * entries never emit a deactivation (SPEC §8): they leave P1 on their own.
 */
internal fun fireEvents(
    device: String,
    now: Instant,
    alarmaId: String,
    programadaPara: Instant,
    snoozed: Boolean,
    alarm: Alarm,
    zone: ZoneId,
): List<Envelope> {
    val out = mutableListOf(alarmaDisparada(device, now, alarmaId, programadaPara))
    if (!snoozed && !isGcalId(alarmaId)) {
        val hasNext = occurrences(alarm.createdAt, alarm.hora, alarm.rule, zone, alarm.fecha)
            .any { it.isAfter(programadaPara) }
        if (!hasNext) out += alarmaDesactivada(device, now, alarmaId, "fin_tratamiento")
    }
    return out
}

/**
 * Events appended by "Hecho": `alarma_atendida(hecho)`; `dosis_dada` for a remedio; and
 * `alarma_desactivada(una_vez_atendida)` for a one-shot own alarm. A `gcal:` entry gets only
 * the `alarma_atendida` (SPEC §8).
 */
internal fun hechoEvents(
    device: String,
    now: Instant,
    alarmaId: String,
    programadaPara: Instant,
    alarm: Alarm?,
): List<Envelope> {
    val out = mutableListOf(alarmaAtendida(device, now, alarmaId, programadaPara, "hecho"))
    if (isGcalId(alarmaId)) return out
    if (alarm != null && alarm.tipo == "remedio" &&
        !alarm.destinatario.isNullOrBlank() && !alarm.remedio.isNullOrBlank()
    ) {
        out += dosisDada(device, now, alarmaId, alarm.destinatario, alarm.remedio, programadaPara)
    }
    if (alarm != null && alarm.rule == null) {
        out += alarmaDesactivada(device, now, alarmaId, "una_vez_atendida")
    }
    return out
}

/** What the editor shows when editing an existing alarm (SPEC §8 `(agente)` web rule, same on Android). */
internal data class EditorPrefill(
    val hora: String,
    /** YYYY-MM-DD or empty. */
    val fecha: String,
    val form: RepeticionForm,
)

/**
 * P1 carries no `fecha`: the date is the local date of `proximo_disparo`; HOURLY also takes
 * its local hora; a series with `count` restarts with `restantes`. Without a P1 row (the
 * calendar ended) the stored state is shown as is.
 */
internal fun editorPrefill(alarm: Alarm, row: AlarmaActiva?, zone: ZoneId): EditorPrefill {
    val form = repeticionFormFromJson(alarm.repeticion)
    if (row == null) {
        return EditorPrefill(alarm.horaRaw, alarm.fecha?.toString().orEmpty(), form)
    }
    val next = Instant.parse(row.proximoDisparo).atZone(zone)
    val hora = if (form.freq == "HOURLY") formatLocalHora(next.toInstant(), zone) else alarm.horaRaw
    val count = if (form.count != null && row.restantes != null) row.restantes else form.count
    return EditorPrefill(hora, next.toLocalDate().toString(), form.copy(count = count))
}
