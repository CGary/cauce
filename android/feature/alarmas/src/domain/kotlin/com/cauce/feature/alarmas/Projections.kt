package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import com.cauce.core.events.projection.Projection
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Serializable
internal data class AlarmaActiva(
    @SerialName("alarma_id") val alarmaId: String,
    val titulo: String,
    val tipo: String,
    val hora: String,
    val repeticion: JsonElement,
    @SerialName("proximo_disparo") val proximoDisparo: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val restantes: Int? = null,
)

@Serializable
internal data class Adherencia(
    @SerialName("alarma_id") val alarmaId: String,
    val destinatario: String,
    val remedio: String,
    @SerialName("dosis_esperadas") val dosisEsperadas: Int,
    @SerialName("dosis_dadas") val dosisDadas: Int,
    val ignoradas: Int,
    @SerialName("ultima_dosis") val ultimaDosis: String? = null,
)

@Serializable
internal data class HistorialDosisEntry(
    val ts: String,
    val destinatario: String,
    val remedio: String,
)

internal object AlarmasActivas : Projection<List<AlarmaActiva>> {
    override val key: String = "alarmas.alarmas_activas"
    override val eventTypes: Set<String> = setOf(
        "alarma_creada",
        "alarma_modificada",
        "alarma_desactivada",
        "reunion_sincronizada",
        "reunion_eliminada_en_origen",
        "aviso_reunion_editado",
    )
    override val serializer: KSerializer<List<AlarmaActiva>> = ListSerializer(AlarmaActiva.serializer())

    override fun compute(events: List<Envelope>, params: JsonObject): List<AlarmaActiva> {
        val now = params.instant("now")
        val zone = params.zone()
        val own = foldAlarms(events)
            .filter { !it.deactivated }
            .mapNotNull { alarm ->
                val next = occurrences(alarm.createdAt, alarm.hora, alarm.rule, zone, alarm.fecha)
                    .firstOrNull { it.isAfter(now) }
                    ?: return@mapNotNull null
                val restantes = if (alarm.rule?.count != null) {
                    occurrences(alarm.createdAt, alarm.hora, alarm.rule, zone, alarm.fecha)
                        .count { firing -> !firing.isBefore(next) }
                } else {
                    null
                }
                AlarmaActiva(
                    alarmaId = alarm.id,
                    titulo = alarm.titulo,
                    tipo = alarm.tipo,
                    hora = alarm.horaRaw,
                    repeticion = alarm.repeticion,
                    proximoDisparo = formatUtc(next),
                    restantes = restantes,
                )
            }
        return (own + googleAlarmas(events, now, zone))
            .sortedWith(compareBy({ Instant.parse(it.proximoDisparo) }, { it.alarmaId }))
    }
}

internal object AdherenciaTratamiento : Projection<List<Adherencia>> {
    override val key: String = "alarmas.adherencia_tratamiento"
    override val eventTypes: Set<String> = setOf(
        "alarma_creada",
        "alarma_modificada",
        "dosis_dada",
        "alarma_atendida",
    )
    override val serializer: KSerializer<List<Adherencia>> = ListSerializer(Adherencia.serializer())

    override fun compute(events: List<Envelope>, params: JsonObject): List<Adherencia> {
        val now = params.instant("now")
        val zone = params.zone()
        val doses = events.filter { it.type == "dosis_dada" }.groupBy { it.payload.string("alarma_id") }
        val ignored = events.filter { it.type == "alarma_atendida" && it.payload.string("accion") == "ignorada" }
            .groupBy { it.payload.string("alarma_id") }
        return foldAlarms(events)
            .filter { it.tipo == "remedio" }
            .sortedBy { it.id }
            .map { alarm ->
                val given = doses[alarm.id].orEmpty()
                val givenSlots = given.map { it.payload.string("programada_para") }.toSet()
                val ignoredSlots = ignored[alarm.id].orEmpty()
                    .map { it.payload.string("programada_para") }
                    .filter { it !in givenSlots }
                    .toSet()
                val ultima = given.maxWithOrNull(compareBy { Instant.parse(it.ts) })
                    ?.let { formatUtc(Instant.parse(it.ts)) }
                Adherencia(
                    alarmaId = alarm.id,
                    destinatario = alarm.destinatario.orEmpty(),
                    remedio = alarm.remedio.orEmpty(),
                    dosisEsperadas = occurrences(alarm.createdAt, alarm.hora, alarm.rule, zone, alarm.fecha)
                        .takeWhile { !it.isAfter(now) }
                        .count(),
                    dosisDadas = givenSlots.size,
                    ignoradas = ignoredSlots.size,
                    ultimaDosis = ultima,
                )
            }
    }
}

internal object HistorialDosis : Projection<List<HistorialDosisEntry>> {
    override val key: String = "alarmas.historial_dosis"
    override val eventTypes: Set<String> = setOf("dosis_dada")
    override val serializer: KSerializer<List<HistorialDosisEntry>> =
        ListSerializer(HistorialDosisEntry.serializer())

    override fun compute(events: List<Envelope>, params: JsonObject): List<HistorialDosisEntry> {
        val filter = params["destinatario"]?.jsonPrimitive?.contentOrNull
        val candidates = events.withIndex()
            .filter { filter == null || it.value.payload.string("destinatario") == filter }
            .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        val kept = linkedMapOf<Pair<String, String>, Envelope>()
        candidates.forEach { (_, event) ->
            val key = event.payload.string("alarma_id") to event.payload.string("programada_para")
            if (key !in kept) kept[key] = event
        }
        return kept.entries
            .map { (key, event) -> Triple(key, formatUtc(Instant.parse(event.ts)), event) }
            .sortedWith(
                compareByDescending<Triple<Pair<String, String>, String, Envelope>> { it.second }
                    .thenBy { it.first.first }
                    .thenBy { it.first.second },
            )
            .map { (_, ts, event) ->
                HistorialDosisEntry(
                    ts = ts,
                    destinatario = event.payload.string("destinatario"),
                    remedio = event.payload.string("remedio"),
                )
            }
    }
}

internal data class Alarm(
    val id: String,
    val createdAt: Instant,
    val titulo: String,
    val tipo: String,
    val hora: LocalTime,
    val horaRaw: String,
    val repeticion: JsonElement,
    val rule: Recurrence?,
    val destinatario: String?,
    val remedio: String?,
    val fecha: LocalDate?,
    val deactivated: Boolean,
)

private data class MutableAlarm(
    var fields: AlarmFields,
    var stateTs: Instant,
    var creadaTs: Instant?,
)

private data class AlarmFields(
    val titulo: String,
    val tipo: String,
    val hora: LocalTime,
    val horaRaw: String,
    val repeticion: JsonElement,
    val rule: Recurrence?,
    val destinatario: String?,
    val remedio: String?,
    val fecha: LocalDate?,
)

internal fun foldAlarms(events: List<Envelope>): List<Alarm> {
    val byId = linkedMapOf<String, MutableAlarm>()
    val deactivated = mutableSetOf<String>()
    events.withIndex()
        .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        .forEach { (_, event) ->
            when (event.type) {
                "alarma_creada" -> if (event.v in 2..3) applyState(byId, event, isCreate = true)
                "alarma_modificada" -> if (event.v in 2..3) applyState(byId, event, isCreate = false)
                "alarma_desactivada" -> deactivated += event.payload.string("alarma_id")
            }
        }
    return byId.map { (id, alarm) ->
        val fields = alarm.fields
        Alarm(
            id = id,
            createdAt = alarm.creadaTs ?: alarm.stateTs,
            titulo = fields.titulo,
            tipo = fields.tipo,
            hora = fields.hora,
            horaRaw = fields.horaRaw,
            repeticion = fields.repeticion,
            rule = fields.rule,
            destinatario = fields.destinatario,
            remedio = fields.remedio,
            fecha = fields.fecha,
            deactivated = id in deactivated,
        )
    }
}

private fun applyState(byId: LinkedHashMap<String, MutableAlarm>, event: Envelope, isCreate: Boolean) {
    val id = event.payload.string("alarma_id")
    val ts = Instant.parse(event.ts)
    val fields = readFields(event.payload)
    val existing = byId[id]
    if (existing == null) {
        byId[id] = MutableAlarm(
            fields = fields,
            stateTs = ts,
            creadaTs = ts.takeIf { isCreate },
        )
        return
    }
    if (!ts.isBefore(existing.stateTs)) {
        existing.fields = fields
        existing.stateTs = ts
    }
    if (isCreate && (existing.creadaTs == null || ts.isAfter(existing.creadaTs))) {
        existing.creadaTs = ts
    }
}

private fun readFields(payload: JsonObject): AlarmFields {
    val horaRaw = payload.string("hora")
    val repeticion = payload["repeticion"] ?: JsonNull
    return AlarmFields(
        titulo = payload.string("titulo"),
        tipo = payload.string("tipo"),
        hora = LocalTime.parse(horaRaw),
        horaRaw = horaRaw,
        repeticion = repeticion,
        rule = parseRecurrence(repeticion),
        destinatario = payload.stringOrNull("destinatario"),
        remedio = payload.stringOrNull("remedio"),
        fecha = payload.stringOrNull("fecha")?.let(LocalDate::parse),
    )
}

@Serializable
internal data class AvisoReunion(
    val origen: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("minutos_antes")
    val minutosAntes: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hora: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val apagado: Boolean? = null,
)

internal fun googleAlarmas(events: List<Envelope>, now: Instant, zone: ZoneId): List<AlarmaActiva> {
    val avisos = foldAvisos(events)
    return foldReuniones(events).mapNotNull { reunion ->
        if (reunion.estado != "confirmada" || reunion.respuesta == "rechazada") return@mapNotNull null
        val (aviso, at) = resolveAviso(reunion, avisos[reunion.serieId], zone)
        if (aviso.apagado == true || at == null || !at.isAfter(now)) return@mapNotNull null
        AlarmaActiva(
            alarmaId = "gcal:${reunion.reunionId}",
            titulo = reunion.titulo,
            tipo = "reunion",
            hora = formatLocalHora(at, zone),
            repeticion = JsonNull,
            proximoDisparo = formatUtc(at),
        )
    }
}

private data class LiveReunion(
    val reunionId: String,
    val serieId: String,
    val cuenta: String,
    val titulo: String,
    val inicio: String,
    val fin: String,
    val inicioAt: Instant,
    val todoElDia: Boolean,
    val recordatorioMin: Int?,
    val estado: String,
    val respuesta: String,
)

private data class AvisoEdit(
    val minutosAntes: Int?,
    val hora: String?,
    val apagado: Boolean,
)

private fun foldReuniones(events: List<Envelope>): List<LiveReunion> {
    val byId = linkedMapOf<String, Pair<Instant, LiveReunion?>>()
    events.withIndex()
        .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        .forEach { (_, event) ->
            when (event.type) {
                "reunion_sincronizada" -> {
                    val id = event.payload.string("reunion_id")
                    val ts = Instant.parse(event.ts)
                    val current = byId[id]
                    if (current == null || !ts.isBefore(current.first)) {
                        byId[id] = ts to readReunion(event)
                    }
                }
                "reunion_eliminada_en_origen" -> {
                    val id = event.payload.string("reunion_id")
                    val ts = Instant.parse(event.ts)
                    val current = byId[id]
                    if (current == null || !ts.isBefore(current.first)) {
                        byId[id] = ts to null
                    }
                }
            }
        }
    return byId.values.mapNotNull { it.second }
}

private fun foldAvisos(events: List<Envelope>): Map<String, AvisoEdit> {
    val bySerie = linkedMapOf<String, Pair<Instant, AvisoEdit>>()
    events.withIndex()
        .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        .forEach { (_, event) ->
            if (event.type != "aviso_reunion_editado") return@forEach
            val id = event.payload.string("serie_id")
            val ts = Instant.parse(event.ts)
            val current = bySerie[id]
            if (current == null || !ts.isBefore(current.first)) {
                bySerie[id] = ts to readAviso(event.payload)
            }
        }
    return bySerie.mapValues { it.value.second }
}

private fun readReunion(event: Envelope): LiveReunion {
    val payload = event.payload
    val inicio = payload.string("inicio")
    return LiveReunion(
        reunionId = payload.string("reunion_id"),
        serieId = payload.string("serie_id"),
        cuenta = payload.string("cuenta"),
        titulo = payload.string("titulo"),
        inicio = inicio,
        fin = payload.string("fin"),
        inicioAt = Instant.parse(inicio),
        todoElDia = payload.bool("todo_el_dia"),
        recordatorioMin = payload.intOrNull("recordatorio_min"),
        estado = payload.string("estado"),
        respuesta = payload.string("respuesta"),
    )
}

private fun readAviso(payload: JsonObject) = AvisoEdit(
    minutosAntes = payload.intOrNull("minutos_antes"),
    hora = payload.stringOrNull("hora"),
    apagado = payload.boolOrNull("apagado") == true,
)

private fun meetingDay(reunion: LiveReunion, zone: ZoneId): LocalDate =
    if (reunion.todoElDia) {
        reunion.inicioAt.atZone(ZoneOffset.UTC).toLocalDate()
    } else {
        reunion.inicioAt.atZone(zone).toLocalDate()
    }

private fun resolveAviso(
    reunion: LiveReunion,
    edit: AvisoEdit?,
    zone: ZoneId,
): Pair<AvisoReunion, Instant?> {
    if (edit?.apagado == true) return AvisoReunion(origen = "cauce", apagado = true) to null
    edit?.minutosAntes?.let { minutes ->
        return AvisoReunion(origen = "cauce", minutosAntes = minutes) to
            reunion.inicioAt.minus(minutes.toLong(), ChronoUnit.MINUTES)
    }
    edit?.hora?.let { hora ->
        val at = atHora(meetingDay(reunion, zone), LocalTime.parse(hora), zone)
        return AvisoReunion(origen = "cauce", hora = hora) to at
    }
    if (reunion.todoElDia) {
        val hora = "08:00"
        val at = atHora(meetingDay(reunion, zone), LocalTime.parse(hora), zone)
        return AvisoReunion(origen = "defecto", hora = hora) to at
    }
    reunion.recordatorioMin?.let { minutes ->
        return AvisoReunion(origen = "google", minutosAntes = minutes) to
            reunion.inicioAt.minus(minutes.toLong(), ChronoUnit.MINUTES)
    }
    return AvisoReunion(origen = "defecto", minutosAntes = 10) to
        reunion.inicioAt.minus(10, ChronoUnit.MINUTES)
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.zone(): ZoneId = ZoneId.of(string("tz"))
