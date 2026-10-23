package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionsGoParityTest {
    private var seq = 0

    private fun event(type: String, ts: String, v: Int = 2, payload: JsonObject) = Envelope(
        id = "e-${seq++}",
        userId = "u1",
        deviceId = "test",
        type = type,
        v = v,
        ts = ts,
        payload = payload,
    )

    private fun alarma(
        id: String,
        hora: String = "08:00",
        tipo: String = "remedio",
        destinatario: String = "Juan",
        remedio: String = "Amoxicilina",
    ) = buildJsonObject {
        put("alarma_id", id)
        put("titulo", "Titulo")
        put("tipo", tipo)
        put("hora", hora)
        put("repeticion", JsonNull)
        put("destinatario", destinatario)
        put("remedio", remedio)
    }

    private fun dosis(id: String, programada: String, destinatario: String, remedio: String) =
        buildJsonObject {
            put("alarma_id", id)
            put("destinatario", destinatario)
            put("remedio", remedio)
            put("programada_para", programada)
        }

    private fun params(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun `two alarma_creada anchor the calendar on the latest created ts`() {
        val events = listOf(
            event("alarma_creada", "2026-10-20T06:00:00Z", payload = alarma("a")),
            event("alarma_creada", "2026-10-20T09:00:00Z", payload = alarma("a")),
        )
        val out = AlarmasActivas.compute(
            events,
            params("now" to "2026-10-20T00:00:00Z", "tz" to "UTC"),
        )
        assertEquals(listOf("2026-10-21T08:00:00Z"), out.map { it.proximoDisparo })
    }

    @Test
    fun `only alarma_modificada anchors on the latest state event ts`() {
        val events = listOf(
            event("alarma_modificada", "2026-10-20T06:00:00Z", payload = alarma("a")),
            event("alarma_modificada", "2026-10-20T09:00:00Z", payload = alarma("a")),
        )
        val out = AlarmasActivas.compute(
            events,
            params("now" to "2026-10-20T00:00:00Z", "tz" to "UTC"),
        )
        assertEquals(listOf("2026-10-21T08:00:00Z"), out.map { it.proximoDisparo })
    }

    @Test
    fun `P3 ties on ts order by alarma_id then programada_para`() {
        val ts = "2026-10-20T08:00:00Z"
        val events = listOf(
            event("dosis_dada", ts, v = 1, payload = dosis("b", "2026-10-20T10:00:00Z", "Juan", "RB10")),
            event("dosis_dada", ts, v = 1, payload = dosis("a", "2026-10-20T12:00:00Z", "Juan", "RA12")),
            event("dosis_dada", ts, v = 1, payload = dosis("a", "2026-10-20T10:00:00Z", "Juan", "RA10")),
        )
        val out = HistorialDosis.compute(events, params())
        assertEquals(listOf("RA10", "RA12", "RB10"), out.map { it.remedio })
        assertEquals(List(3) { "2026-10-20T08:00:00Z" }, out.map { it.ts })
    }

    @Test
    fun `P3 filters by destinatario before deduplicating`() {
        val events = listOf(
            event(
                "dosis_dada",
                "2026-10-20T08:00:00Z",
                v = 1,
                payload = dosis("a", "2026-10-20T10:00:00Z", "Ana", "RA"),
            ),
            event(
                "dosis_dada",
                "2026-10-20T09:00:00Z",
                v = 1,
                payload = dosis("a", "2026-10-20T10:00:00Z", "Juan", "RJ"),
            ),
        )
        val out = HistorialDosis.compute(events, params("destinatario" to "Juan"))
        assertEquals(
            listOf(HistorialDosisEntry(ts = "2026-10-20T09:00:00Z", destinatario = "Juan", remedio = "RJ")),
            out,
        )
    }

    @Test
    fun `timestamps are normalized to UTC without fractional seconds`() {
        val alarmEvents = listOf(
            event("alarma_creada", "2026-10-19T00:00:00Z", payload = alarma("a")),
            event(
                "dosis_dada",
                "2026-10-20T10:00:00-02:00",
                v = 1,
                payload = dosis("a", "2026-10-20T08:00:00Z", "Juan", "Amoxicilina"),
            ),
        )
        val adherencia = AdherenciaTratamiento.compute(
            alarmEvents,
            params("now" to "2026-10-20T12:00:00Z", "tz" to "UTC"),
        )
        assertEquals(listOf("2026-10-20T12:00:00Z"), adherencia.map { it.ultimaDosis })

        val historial = HistorialDosis.compute(
            listOf(
                event(
                    "dosis_dada",
                    "2026-10-20T08:00:00.500Z",
                    v = 1,
                    payload = dosis("a", "2026-10-20T08:00:00Z", "Juan", "Amoxicilina"),
                ),
            ),
            params(),
        )
        assertEquals(listOf("2026-10-20T08:00:00Z"), historial.map { it.ts })
    }
}
