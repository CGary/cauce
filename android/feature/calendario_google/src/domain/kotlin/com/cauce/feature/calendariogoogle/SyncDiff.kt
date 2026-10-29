package com.cauce.feature.calendariogoogle

import com.cauce.core.events.Envelope
import com.cauce.core.events.UuidV7
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the sync must append to the log (SPEC section 5). */
internal sealed interface SyncAction {
    data class Sincronizar(val reunion: ReunionSincronizada) : SyncAction
    data class Eliminar(val reunionId: String) : SyncAction
}

/** How far ahead the phone looks (SPEC 2.6). */
internal val SYNC_WINDOW: Duration = Duration.ofDays(14)

/** Last `reunion_sincronizada` per live `reunion_id`, folded from this feature's own events. */
internal fun previousState(events: List<Envelope>): Map<String, ReunionSincronizada> =
    foldReuniones(events).associateBy { it.reunionId }

/**
 * Pure diff between the last emitted state and the calendar reading.
 *
 * - `current == null` (permission denied, provider unavailable) or empty: nothing, so a failed
 *   read can never produce eliminations.
 * - New or changed occurrence: [SyncAction.Sincronizar]; unchanged: nothing.
 * - Occurrence emitted before, with `inicio` after [now] and inside the window, now missing:
 *   [SyncAction.Eliminar]. One that merely moved into the past is left alone.
 */
internal fun syncDiff(
    previous: Map<String, ReunionSincronizada>,
    current: List<ReunionSincronizada>?,
    now: Instant,
    windowEnd: Instant = now.plus(SYNC_WINDOW),
): List<SyncAction> {
    if (current.isNullOrEmpty()) return emptyList()
    val currentById = current.associateBy { it.reunionId }
    val upserts = current
        .filter { previous[it.reunionId] != it }
        .map { SyncAction.Sincronizar(it) }
    val removals = previous.values
        .filter { it.reunionId !in currentById }
        .filter {
            val inicio = Instant.parse(it.inicio)
            inicio.isAfter(now) && !inicio.isAfter(windowEnd)
        }
        .map { it.reunionId }
        .sorted()
        .map { SyncAction.Eliminar(it) }
    return upserts + removals
}

internal const val DEFAULT_USER_ID: String = "u1"

private val UTC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

internal fun formatUtc(instant: Instant): String = UTC_FORMAT.format(instant)

private val HORA = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

private fun envelope(deviceId: String, type: String, ts: Instant, payload: JsonObject) = Envelope(
    id = UuidV7.newId(),
    userId = DEFAULT_USER_ID,
    deviceId = deviceId,
    type = type,
    v = 1,
    ts = formatUtc(ts),
    payload = payload,
)

internal fun reunionSincronizadaEvent(deviceId: String, ts: Instant, r: ReunionSincronizada): Envelope {
    val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "reunion_id" to JsonPrimitive(r.reunionId),
        "serie_id" to JsonPrimitive(r.serieId),
        "cuenta" to JsonPrimitive(r.cuenta),
    )
    r.calendario?.let { fields["calendario"] = JsonPrimitive(it) }
    fields["titulo"] = JsonPrimitive(r.titulo)
    fields["inicio"] = JsonPrimitive(r.inicio)
    fields["fin"] = JsonPrimitive(r.fin)
    fields["todo_el_dia"] = JsonPrimitive(r.todoElDia)
    fields["recordatorio_min"] = r.recordatorioMin?.let { JsonPrimitive(it) } ?: JsonNull
    fields["estado"] = JsonPrimitive(r.estado)
    fields["respuesta"] = JsonPrimitive(r.respuesta)
    return envelope(deviceId, "reunion_sincronizada", ts, JsonObject(fields))
}

internal fun reunionEliminadaEvent(deviceId: String, ts: Instant, reunionId: String): Envelope =
    envelope(deviceId, "reunion_eliminada_en_origen", ts, JsonObject(mapOf("reunion_id" to JsonPrimitive(reunionId))))

internal fun syncActionEvent(deviceId: String, ts: Instant, action: SyncAction): Envelope = when (action) {
    is SyncAction.Sincronizar -> reunionSincronizadaEvent(deviceId, ts, action.reunion)
    is SyncAction.Eliminar -> reunionEliminadaEvent(deviceId, ts, action.reunionId)
}

/**
 * `aviso_reunion_editado` v1 with exactly one of `minutos_antes` (>= 0), `hora` ("HH:MM") or
 * `apagado: true` (SPEC section 3). Throws [IllegalArgumentException] otherwise.
 */
internal fun avisoReunionEditadoEvent(
    deviceId: String,
    ts: Instant,
    serieId: String,
    minutosAntes: Int? = null,
    hora: String? = null,
    apagado: Boolean = false,
): Envelope {
    require(serieId.isNotBlank()) { "serie_id vacío" }
    require(listOf(minutosAntes != null, hora != null, apagado).count { it } == 1) {
        "aviso_reunion_editado lleva exactamente una de minutos_antes, hora, apagado"
    }
    val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("serie_id" to JsonPrimitive(serieId))
    if (minutosAntes != null) {
        require(minutosAntes >= 0) { "minutos_antes debe ser >= 0" }
        fields["minutos_antes"] = JsonPrimitive(minutosAntes)
    }
    if (hora != null) {
        require(HORA.matches(hora)) { "hora debe ser HH:MM" }
        fields["hora"] = JsonPrimitive(hora)
    }
    if (apagado) fields["apagado"] = JsonPrimitive(true)
    return envelope(deviceId, "aviso_reunion_editado", ts, JsonObject(fields))
}

/** The aviso in words, for the list (SPEC section 5). */
internal fun describeAviso(aviso: AvisoVigente): String {
    val base = when {
        aviso.apagado == true -> return "Aviso apagado"
        aviso.hora != null -> "a las ${aviso.hora}"
        aviso.minutosAntes == 0 -> "al inicio"
        aviso.minutosAntes != null -> "${aviso.minutosAntes} min antes"
        else -> "sin aviso"
    }
    val origen = when (aviso.origen) {
        "google" -> "de Google"
        "cauce" -> "editado"
        else -> "por defecto"
    }
    return "Aviso $base ($origen)"
}
