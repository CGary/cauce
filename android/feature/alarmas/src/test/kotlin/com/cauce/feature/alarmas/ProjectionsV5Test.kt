package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectionsV5Test {
    private val zone = ZoneId.of("America/La_Paz")
    private var seq = 0

    private fun event(type: String, ts: String, v: Int = 1, payload: JsonObject) = Envelope(
        id = "e-${seq++}",
        userId = "u1",
        deviceId = "test",
        type = type,
        v = v,
        ts = ts,
        payload = payload,
    )

    private fun params(now: String = "2026-11-02T12:00:00Z") = buildJsonObject {
        put("now", now)
        put("tz", "America/La_Paz")
    }

    private fun alarma(
        id: String,
        hora: String,
        fecha: String,
        repeticion: JsonObject,
        titulo: String = id,
    ) = buildJsonObject {
        put("alarma_id", id)
        put("titulo", titulo)
        put("tipo", "otro")
        put("hora", hora)
        put("repeticion", repeticion)
        put("fecha", fecha)
    }

    private fun reunion(
        id: String,
        inicio: String,
        fin: String,
        todoElDia: Boolean = false,
        recordatorio: Int? = 10,
        estado: String = "confirmada",
        respuesta: String = "aceptada",
        serie: String = id,
        titulo: String = id,
    ) = buildJsonObject {
        put("reunion_id", id)
        put("serie_id", serie)
        put("cuenta", "ana@example.com")
        put("titulo", titulo)
        put("inicio", inicio)
        put("fin", fin)
        put("todo_el_dia", todoElDia)
        put("recordatorio_min", recordatorio?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("estado", estado)
        put("respuesta", respuesta)
    }

    @Test
    fun `fecha MONTHLY count starts at fecha and includes that day`() {
        val rule = Recurrence(freq = Freq.MONTHLY, monthDay = 15, count = 4)
        assertEquals(
            listOf(
                "2026-11-15T13:00:00Z",
                "2026-12-15T13:00:00Z",
                "2027-01-15T13:00:00Z",
                "2027-02-15T13:00:00Z",
            ),
            occurrences(
                Instant.parse("2026-01-01T12:00:00Z"),
                LocalTime.parse("09:00"),
                rule,
                zone,
                LocalDate.parse("2026-11-15"),
            ).map(::formatUtc).toList(),
        )

        val out = AlarmasActivas.compute(
            listOf(
                event(
                    "alarma_creada",
                    "2026-01-01T12:00:00Z",
                    v = 3,
                    payload = alarma(
                        "m1",
                        "09:00",
                        "2026-11-15",
                        buildJsonObject {
                            put("freq", "MONTHLY")
                            put("bymonthday", 15)
                            put("count", 4)
                        },
                    ),
                ),
            ),
            params(),
        )
        assertEquals(listOf("2026-11-15T13:00:00Z"), out.map { it.proximoDisparo })
        assertEquals(listOf(4), out.map { it.restantes })
    }

    @Test
    fun `fecha on a non-matching WEEKLY day moves forward and anchors the interval there`() {
        val rule = Recurrence(
            freq = Freq.WEEKLY,
            interval = 2,
            weekdays = setOf(java.time.DayOfWeek.FRIDAY),
            count = 2,
        )
        assertEquals(
            listOf("2026-11-13T12:00:00Z", "2026-11-27T12:00:00Z"),
            occurrences(
                Instant.parse("2026-10-20T12:00:00Z"),
                LocalTime.parse("08:00"),
                rule,
                zone,
                LocalDate.parse("2026-11-08"),
            ).map(::formatUtc).toList(),
        )

        val out = AlarmasActivas.compute(
            listOf(
                event(
                    "alarma_creada",
                    "2026-10-20T12:00:00Z",
                    v = 3,
                    payload = alarma(
                        "w1",
                        "08:00",
                        "2026-11-08",
                        buildJsonObject {
                            put("freq", "WEEKLY")
                            put("interval", 2)
                            put("byday", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("FR")) })
                        },
                    ),
                ),
            ),
            params(),
        )
        assertEquals("2026-11-13T12:00:00Z", out.single().proximoDisparo)
    }

    @Test
    fun `all-day meeting defaults to 08_00 local`() {
        val out = AlarmasActivas.compute(
            listOf(
                event(
                    "reunion_sincronizada",
                    "2026-11-01T20:00:00Z",
                    payload = reunion(
                        "d1",
                        inicio = "2026-11-05T00:00:00Z",
                        fin = "2026-11-06T00:00:00Z",
                        todoElDia = true,
                        recordatorio = 1440,
                        titulo = "Cumpleaños",
                    ),
                ),
            ),
            params(),
        )
        val row = out.single()
        assertEquals("gcal:d1", row.alarmaId)
        assertEquals("reunion", row.tipo)
        assertEquals("08:00", row.hora)
        assertEquals(JsonNull, row.repeticion)
        assertEquals("2026-11-05T12:00:00Z", row.proximoDisparo)
        assertNull(row.restantes)
    }

    @Test
    fun `edited hora is the local clock time on the meeting day`() {
        val out = AlarmasActivas.compute(
            listOf(
                event(
                    "reunion_sincronizada",
                    "2026-11-01T20:00:00Z",
                    payload = reunion(
                        "d2",
                        inicio = "2026-11-06T00:00:00Z",
                        fin = "2026-11-07T00:00:00Z",
                        todoElDia = true,
                        recordatorio = 1440,
                        serie = "s2",
                    ),
                ),
                event(
                    "aviso_reunion_editado",
                    "2026-11-01T21:00:00Z",
                    payload = buildJsonObject {
                        put("serie_id", "s2")
                        put("hora", "07:00")
                    },
                ),
            ),
            params(),
        )
        assertEquals("07:00", out.single().hora)
        assertEquals("2026-11-06T11:00:00Z", out.single().proximoDisparo)
    }

    @Test
    fun `minutos_antes edit overrides recordatorio_min and a later same-ts edit wins`() {
        val out = AlarmasActivas.compute(
            listOf(
                event(
                    "reunion_sincronizada",
                    "2026-11-01T20:00:00Z",
                    payload = reunion(
                        "r1",
                        inicio = "2026-11-03T14:00:00Z",
                        fin = "2026-11-03T15:00:00Z",
                        recordatorio = 30,
                        serie = "s1",
                        titulo = "Daily",
                    ),
                ),
                event(
                    "aviso_reunion_editado",
                    "2026-11-01T21:00:00Z",
                    payload = buildJsonObject {
                        put("serie_id", "s1")
                        put("minutos_antes", 60)
                    },
                ),
                event(
                    "aviso_reunion_editado",
                    "2026-11-01T21:00:00Z",
                    payload = buildJsonObject {
                        put("serie_id", "s1")
                        put("minutos_antes", 5)
                    },
                ),
            ),
            params(),
        )
        assertEquals("09:55", out.single().hora)
        assertEquals("2026-11-03T13:55:00Z", out.single().proximoDisparo)
    }

    @Test
    fun `eliminada_en_origen drops the meeting unless a later or same-ts sync revives it`() {
        val live = reunion(
            "r2",
            inicio = "2026-11-04T14:00:00Z",
            fin = "2026-11-04T15:00:00Z",
            titulo = "Revivida",
        )
        val out = AlarmasActivas.compute(
            listOf(
                event("reunion_sincronizada", "2026-11-01T20:00:00Z", payload = reunion("r1", "2026-11-04T14:00:00Z", "2026-11-04T15:00:00Z")),
                event("reunion_eliminada_en_origen", "2026-11-02T08:00:00Z", payload = buildJsonObject { put("reunion_id", "r1") }),
                event("reunion_eliminada_en_origen", "2026-11-01T20:00:00Z", payload = buildJsonObject { put("reunion_id", "r2") }),
                event("reunion_sincronizada", "2026-11-02T08:00:00Z", payload = live),
                event("reunion_sincronizada", "2026-11-01T21:00:00Z", payload = reunion("r3", "2026-11-05T14:00:00Z", "2026-11-05T15:00:00Z")),
                event("reunion_eliminada_en_origen", "2026-11-01T21:00:00Z", payload = buildJsonObject { put("reunion_id", "r3") }),
                event("reunion_eliminada_en_origen", "2026-11-01T22:00:00Z", payload = buildJsonObject { put("reunion_id", "r4") }),
                event(
                    "reunion_sincronizada",
                    "2026-11-01T22:00:00Z",
                    payload = reunion("r4", "2026-11-06T14:00:00Z", "2026-11-06T15:00:00Z", titulo = "Tie revive"),
                ),
            ),
            params(),
        )
        assertEquals(listOf("gcal:r2", "gcal:r4"), out.map { it.alarmaId })
        assertEquals("Revivida", out.first().titulo)
        assertEquals("Tie revive", out.last().titulo)
    }

    @Test
    fun `cancelada meetings do not appear`() {
        val out = AlarmasActivas.compute(
            listOf(
                event(
                    "reunion_sincronizada",
                    "2026-11-01T20:00:00Z",
                    payload = reunion("c1", "2026-11-03T16:00:00Z", "2026-11-03T17:00:00Z", estado = "cancelada"),
                ),
                event(
                    "reunion_sincronizada",
                    "2026-11-01T20:00:00Z",
                    payload = reunion("k1", "2026-11-03T18:00:00Z", "2026-11-03T19:00:00Z", titulo = "Sigue"),
                ),
            ),
            params(),
        )
        assertEquals(listOf("gcal:k1"), out.map { it.alarmaId })
    }
}
