package com.cauce.feature.calendariogoogle

import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import com.cauce.core.events.projection.Projection
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Serializable
internal data class ReunionSincronizada(
    @SerialName("reunion_id") val reunionId: String,
    @SerialName("serie_id") val serieId: String,
    val cuenta: String,
    val calendario: String? = null,
    val titulo: String,
    val inicio: String,
    val fin: String,
    @SerialName("todo_el_dia") val todoElDia: Boolean,
    @SerialName("recordatorio_min") val recordatorioMin: Int? = null,
    val estado: String,
    val respuesta: String,
)

@Serializable
internal data class ReunionEliminadaEnOrigen(
    @SerialName("reunion_id") val reunionId: String,
)

@Serializable
internal data class AvisoReunionEditado(
    @SerialName("serie_id") val serieId: String,
    @SerialName("minutos_antes") val minutosAntes: Int? = null,
    val hora: String? = null,
    val apagado: Boolean? = null,
)

@Serializable
internal data class AvisoVigente(
    val origen: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("minutos_antes")
    val minutosAntes: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hora: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apagado: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ReunionProxima(
    @SerialName("reunion_id") val reunionId: String,
    @SerialName("serie_id") val serieId: String,
    val cuenta: String,
    val titulo: String,
    val inicio: String,
    val fin: String,
    @SerialName("todo_el_dia") val todoElDia: Boolean,
    val aviso: AvisoVigente,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("aviso_en") val avisoEn: String?,
)

internal object ReunionesProximas : Projection<List<ReunionProxima>> {
    override val key: String = "calendario_google.reuniones_proximas"
    override val eventTypes: Set<String> = setOf(
        "reunion_sincronizada",
        "reunion_eliminada_en_origen",
        "aviso_reunion_editado",
    )
    override val serializer: KSerializer<List<ReunionProxima>> =
        ListSerializer(ReunionProxima.serializer())

    override fun compute(events: List<Envelope>, params: JsonObject): List<ReunionProxima> {
        val now = Instant.parse(params.getValue("now").jsonPrimitive.content)
        val zone = ZoneId.of(params.getValue("tz").jsonPrimitive.content)
        val edits = foldAvisos(events)
        return foldReuniones(events)
            .filter { it.estado == "confirmada" && it.respuesta != "rechazada" }
            .filter { Instant.parse(it.inicio).isAfter(now) }
            .map { r ->
                val (aviso, at) = resolveAviso(r, edits[r.serieId], zone)
                ReunionProxima(
                    reunionId = r.reunionId,
                    serieId = r.serieId,
                    cuenta = r.cuenta,
                    titulo = r.titulo,
                    inicio = r.inicio,
                    fin = r.fin,
                    todoElDia = r.todoElDia,
                    aviso = aviso,
                    avisoEn = at?.let(UTC_FORMAT::format),
                )
            }
            .sortedWith(compareBy({ Instant.parse(it.inicio) }, { it.reunionId }))
    }
}

private val UTC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

private inline fun <reified T> Envelope.decode(): T =
    CauceJson.wire.decodeFromJsonElement<T>(payload)

/** Last event by `ts` per `reunion_id`; ties go to the later one in the input. Null = eliminada. */
internal fun foldReuniones(events: List<Envelope>): List<ReunionSincronizada> {
    val byId = linkedMapOf<String, ReunionSincronizada?>()
    events.withIndex()
        .filter { it.value.type == "reunion_sincronizada" || it.value.type == "reunion_eliminada_en_origen" }
        .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        .forEach { (_, event) ->
            if (event.type == "reunion_sincronizada") {
                val r = event.decode<ReunionSincronizada>()
                byId[r.reunionId] = r
            } else {
                byId[event.decode<ReunionEliminadaEnOrigen>().reunionId] = null
            }
        }
    return byId.values.filterNotNull()
}

private fun foldAvisos(events: List<Envelope>): Map<String, AvisoReunionEditado> {
    val bySerie = linkedMapOf<String, AvisoReunionEditado>()
    events.withIndex()
        .filter { it.value.type == "aviso_reunion_editado" }
        .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        .forEach { (_, event) ->
            val a = event.decode<AvisoReunionEditado>()
            bySerie[a.serieId] = a
        }
    return bySerie
}

private fun meetingDay(r: ReunionSincronizada, zone: ZoneId): LocalDate {
    val inicio = Instant.parse(r.inicio)
    return if (r.todoElDia) inicio.atZone(ZoneOffset.UTC).toLocalDate() else inicio.atZone(zone).toLocalDate()
}

private fun atHora(date: LocalDate, hora: String, zone: ZoneId): Instant =
    ZonedDateTime.of(date, LocalTime.parse(hora), zone).toInstant()

/** SPEC section 4 cascade: edit, then all-day 08:00, then Google reminder, then 10 minutes. */
internal fun resolveAviso(
    r: ReunionSincronizada,
    edit: AvisoReunionEditado?,
    zone: ZoneId,
): Pair<AvisoVigente, Instant?> {
    val inicio = Instant.parse(r.inicio)
    if (edit?.apagado == true) return AvisoVigente(origen = "cauce", apagado = true) to null
    edit?.minutosAntes?.let {
        return AvisoVigente(origen = "cauce", minutosAntes = it) to inicio.minus(it.toLong(), ChronoUnit.MINUTES)
    }
    edit?.hora?.let {
        return AvisoVigente(origen = "cauce", hora = it) to atHora(meetingDay(r, zone), it, zone)
    }
    if (r.todoElDia) {
        return AvisoVigente(origen = "defecto", hora = "08:00") to atHora(meetingDay(r, zone), "08:00", zone)
    }
    r.recordatorioMin?.let {
        return AvisoVigente(origen = "google", minutosAntes = it) to inicio.minus(it.toLong(), ChronoUnit.MINUTES)
    }
    return AvisoVigente(origen = "defecto", minutosAntes = 10) to inicio.minus(10, ChronoUnit.MINUTES)
}
