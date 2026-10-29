package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import com.cauce.core.events.UuidV7
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Builders for the events this feature emits on Android (SPEC §3). Pure: the clock arrives as
 * `ts`, the device id as `deviceId`, so they are JVM-testable. The full-state rule (AGENTS.md:
 * "edits are full-state events") is why `alarma_creada` and `alarma_modificada` share one payload
 * builder.
 */
internal const val DEFAULT_USER_ID: String = "u1"

private val HORA = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
private val FECHA = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val TIPOS = setOf("remedio", "reunion", "otro")
private val MOTIVOS_DESACTIVADA = setOf("usuario", "fin_tratamiento", "una_vez_atendida")
private val ACCIONES_ATENDIDA = setOf("hecho", "pospuesta", "ignorada")

internal fun newEnvelope(
    deviceId: String,
    type: String,
    v: Int,
    ts: Instant,
    payload: JsonObject,
    id: String = UuidV7.newId(),
): Envelope = Envelope(
    id = id,
    userId = DEFAULT_USER_ID,
    deviceId = deviceId,
    type = type,
    v = v,
    ts = formatUtc(ts),
    payload = payload,
)

/** Full state of an alarm: the payload shared by `alarma_creada` v3 and `alarma_modificada` v3 (`fecha` omitted when empty). */
internal fun alarmaStatePayload(
    alarmaId: String,
    titulo: String,
    tipo: String,
    hora: String,
    repeticion: JsonElement,
    destinatario: String? = null,
    remedio: String? = null,
    fecha: String? = null,
): JsonObject {
    require(alarmaId.isNotBlank()) { "alarma_id vacío" }
    require(titulo.isNotBlank() && titulo.length <= 120) { "titulo: 1..120 caracteres" }
    require(tipo in TIPOS) { "tipo inválido: $tipo" }
    require(HORA.matches(hora)) { "hora inválida: $hora" }
    if (fecha != null) {
        require(FECHA.matches(fecha)) { "fecha inválida: $fecha" }
        require(runCatching { java.time.LocalDate.parse(fecha) }.isSuccess) { "fecha inválida: $fecha" }
    }
    if (tipo == "remedio") {
        require(!destinatario.isNullOrBlank() && destinatario.length <= 60) {
            "tipo remedio exige destinatario (1..60)"
        }
        require(!remedio.isNullOrBlank() && remedio.length <= 120) { "tipo remedio exige remedio (1..120)" }
    }
    val fields = mutableMapOf<String, JsonElement>(
        "alarma_id" to JsonPrimitive(alarmaId),
        "titulo" to JsonPrimitive(titulo),
        "tipo" to JsonPrimitive(tipo),
        "hora" to JsonPrimitive(hora),
        "repeticion" to repeticion,
    )
    if (destinatario != null) fields["destinatario"] = JsonPrimitive(destinatario)
    if (remedio != null) fields["remedio"] = JsonPrimitive(remedio)
    if (fecha != null) fields["fecha"] = JsonPrimitive(fecha)
    return JsonObject(fields)
}

internal fun alarmaCreada(
    deviceId: String,
    ts: Instant,
    alarmaId: String,
    titulo: String,
    tipo: String,
    hora: String,
    repeticion: JsonElement,
    destinatario: String? = null,
    remedio: String? = null,
    fecha: String? = null,
    id: String = UuidV7.newId(),
): Envelope = newEnvelope(
    deviceId, "alarma_creada", 3, ts,
    alarmaStatePayload(alarmaId, titulo, tipo, hora, repeticion, destinatario, remedio, fecha),
    id = id,
)

internal fun alarmaModificada(
    deviceId: String,
    ts: Instant,
    alarmaId: String,
    titulo: String,
    tipo: String,
    hora: String,
    repeticion: JsonElement,
    destinatario: String? = null,
    remedio: String? = null,
    fecha: String? = null,
    id: String = UuidV7.newId(),
): Envelope = newEnvelope(
    deviceId, "alarma_modificada", 3, ts,
    alarmaStatePayload(alarmaId, titulo, tipo, hora, repeticion, destinatario, remedio, fecha),
    id = id,
)

internal fun alarmaDesactivada(
    deviceId: String,
    ts: Instant,
    alarmaId: String,
    motivo: String,
    id: String = UuidV7.newId(),
): Envelope {
    require(motivo in MOTIVOS_DESACTIVADA) { "motivo inválido: $motivo" }
    return newEnvelope(
        deviceId, "alarma_desactivada", 1, ts,
        JsonObject(
            mapOf(
                "alarma_id" to JsonPrimitive(alarmaId),
                "motivo" to JsonPrimitive(motivo),
            ),
        ),
        id = id,
    )
}

internal fun alarmaDisparada(
    deviceId: String,
    ts: Instant,
    alarmaId: String,
    programadaPara: Instant,
    id: String = UuidV7.newId(),
): Envelope = newEnvelope(
    deviceId, "alarma_disparada", 1, ts,
    JsonObject(
        mapOf(
            "alarma_id" to JsonPrimitive(alarmaId),
            "programada_para" to JsonPrimitive(formatUtc(programadaPara)),
        ),
    ),
    id = id,
)

internal fun alarmaAtendida(
    deviceId: String,
    ts: Instant,
    alarmaId: String,
    programadaPara: Instant,
    accion: String,
    pospuestaN: Int? = null,
    id: String = UuidV7.newId(),
): Envelope {
    require(accion in ACCIONES_ATENDIDA) { "accion inválida: $accion" }
    if (accion == "pospuesta") {
        require(pospuestaN != null && pospuestaN in 1..3) { "pospuesta_n debe ser 1..3" }
    } else {
        require(pospuestaN == null) { "pospuesta_n solo aplica a accion=pospuesta" }
    }
    val fields = mutableMapOf<String, JsonElement>(
        "alarma_id" to JsonPrimitive(alarmaId),
        "programada_para" to JsonPrimitive(formatUtc(programadaPara)),
        "accion" to JsonPrimitive(accion),
    )
    if (pospuestaN != null) fields["pospuesta_n"] = JsonPrimitive(pospuestaN)
    return newEnvelope(deviceId, "alarma_atendida", 1, ts, JsonObject(fields), id = id)
}

internal fun dosisDada(
    deviceId: String,
    ts: Instant,
    alarmaId: String,
    destinatario: String,
    remedio: String,
    programadaPara: Instant,
    id: String = UuidV7.newId(),
): Envelope {
    require(destinatario.isNotBlank()) { "destinatario vacío" }
    require(remedio.isNotBlank()) { "remedio vacío" }
    return newEnvelope(
        deviceId, "dosis_dada", 1, ts,
        JsonObject(
            mapOf(
                "alarma_id" to JsonPrimitive(alarmaId),
                "destinatario" to JsonPrimitive(destinatario),
                "remedio" to JsonPrimitive(remedio),
                "programada_para" to JsonPrimitive(formatUtc(programadaPara)),
            ),
        ),
        id = id,
    )
}
