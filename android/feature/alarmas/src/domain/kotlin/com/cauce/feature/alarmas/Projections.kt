package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import com.cauce.core.events.projection.Projection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Serializable
internal data class AlarmaActiva(
    @SerialName("alarma_id") val alarmaId: String,
    val titulo: String,
    val tipo: String,
    val hora: String,
    val repeticion: JsonElement,
    @SerialName("proximo_disparo") val proximoDisparo: String,
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
    )
    override val serializer: KSerializer<List<AlarmaActiva>> = ListSerializer(AlarmaActiva.serializer())

    override fun compute(events: List<Envelope>, params: JsonObject): List<AlarmaActiva> {
        val now = params.instant("now")
        val zone = params.zone()
        return foldAlarms(events)
            .filter { !it.deactivated }
            .mapNotNull { alarm ->
                val next = occurrences(alarm.createdAt, alarm.hora, alarm.rule, zone)
                    .firstOrNull { it.isAfter(now) }
                    ?: return@mapNotNull null
                next to alarm
            }
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { (next, alarm) ->
                AlarmaActiva(
                    alarmaId = alarm.id,
                    titulo = alarm.titulo,
                    tipo = alarm.tipo,
                    hora = alarm.horaRaw,
                    repeticion = alarm.repeticion,
                    proximoDisparo = formatUtc(next),
                )
            }
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
                    dosisEsperadas = occurrences(alarm.createdAt, alarm.hora, alarm.rule, zone)
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

private data class Alarm(
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
)

private fun foldAlarms(events: List<Envelope>): List<Alarm> {
    val byId = linkedMapOf<String, MutableAlarm>()
    val deactivated = mutableSetOf<String>()
    events.withIndex()
        .sortedWith(compareBy({ Instant.parse(it.value.ts) }, { it.index }))
        .forEach { (_, event) ->
            when (event.type) {
                "alarma_creada" -> if (event.v == 2) applyState(byId, event, isCreate = true)
                "alarma_modificada" -> if (event.v == 2) applyState(byId, event, isCreate = false)
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
    )
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.instant(key: String): Instant = Instant.parse(string(key))

private fun JsonObject.zone(): ZoneId = ZoneId.of(string("tz"))
