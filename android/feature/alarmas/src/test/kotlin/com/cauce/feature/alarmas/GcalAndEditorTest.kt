package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GcalAndEditorTest {
    private val zone = ZoneId.of("America/La_Paz")
    private val ts = Instant.parse("2026-11-01T12:00:00Z")

    private fun reunionEvent(id: String = "r1") = Envelope(
        id = "e-$id", userId = "u1", deviceId = "test", type = "reunion_sincronizada", v = 1,
        ts = "2026-11-01T10:00:00Z",
        payload = buildJsonObject {
            put("reunion_id", id)
            put("serie_id", id)
            put("cuenta", "ana@example.com")
            put("titulo", "Revisión")
            put("inicio", "2026-11-03T14:00:00Z")
            put("fin", "2026-11-03T15:00:00Z")
            put("todo_el_dia", false)
            put("recordatorio_min", 10)
            put("estado", "confirmada")
            put("respuesta", "aceptada")
        },
    )

    private val aviso = Instant.parse("2026-11-03T13:50:00Z")

    @Test
    fun `v3 payload carries fecha when given and omits it when empty`() {
        val con = alarmaCreada("t", ts, "a1", "X", "otro", "16:00", JsonNull, fecha = "2026-11-10")
        assertEquals(3, con.v)
        assertEquals(JsonPrimitive("2026-11-10"), con.payload["fecha"])
        val sin = alarmaModificada("t", ts, "a1", "X", "otro", "16:00", JsonNull)
        assertEquals(3, sin.v)
        assertFalse(sin.payload.containsKey("fecha"))
        assertFailsWith<IllegalArgumentException> {
            alarmaCreada("t", ts, "a1", "X", "otro", "16:00", JsonNull, fecha = "2026-13-40")
        }
    }

    // Re-anchoring tests use a non-remedio alarm: a remedio is never re-anchored (SPEC 2.2).
    private fun alarm(
        rep: JsonObject?,
        hora: String = "08:00",
        fecha: String? = null,
        tipo: String = "otro",
    ) = Alarm(
        id = "a1", createdAt = ts, titulo = "X", tipo = tipo,
        hora = java.time.LocalTime.parse(hora), horaRaw = hora,
        repeticion = rep ?: JsonNull, rule = parseRecurrence(rep ?: JsonNull),
        destinatario = "Juan", remedio = "Amox", fecha = fecha?.let(java.time.LocalDate::parse),
        deactivated = false,
    )

    private fun row(next: String, restantes: Int? = null) = AlarmaActiva(
        alarmaId = "a1", titulo = "X", tipo = "otro", hora = "08:00",
        repeticion = JsonNull, proximoDisparo = next, restantes = restantes,
    )

    @Test
    fun `prefill uses local date of proximo_disparo and restantes for count`() {
        val rep = RepeticionForm(freq = "DAILY", count = 10).toJson() as JsonObject
        val p = editorPrefill(alarm(rep), row("2026-11-05T12:00:00Z", 6), zone)
        assertEquals("2026-11-05", p.fecha)
        assertEquals("08:00", p.hora)
        assertEquals(6, p.form.count)
    }

    @Test
    fun `prefill date is local not utc`() {
        val p = editorPrefill(alarm(null), row("2026-11-06T02:00:00Z"), zone)
        assertEquals("2026-11-05", p.fecha)
    }

    @Test
    fun `hourly prefill takes hora from proximo_disparo`() {
        val rep = RepeticionForm(freq = "HOURLY", interval = 8, count = 15).toJson() as JsonObject
        val p = editorPrefill(alarm(rep), row("2026-11-05T20:00:00Z", 9), zone)
        assertEquals("16:00", p.hora)
        assertEquals(9, p.form.count)
    }

    @Test
    fun `without a row the stored state is kept`() {
        val p = editorPrefill(alarm(null, fecha = "2026-11-10"), null, zone)
        assertEquals("2026-11-10", p.fecha)
        assertEquals("08:00", p.hora)
    }

    @Test
    fun `remedio prefill keeps the stored schedule even with a P1 row`() {
        val rep = RepeticionForm(freq = "HOURLY", interval = 8, count = 15).toJson() as JsonObject
        val remedio = alarm(rep, fecha = "2026-11-01", tipo = "remedio")
        val p = editorPrefill(remedio, row("2026-11-05T20:00:00Z", 9), zone)
        assertEquals("2026-11-01", p.fecha)
        assertEquals("08:00", p.hora)
        assertEquals(15, p.form.count)
        val sinFecha = editorPrefill(alarm(rep, tipo = "remedio"), row("2026-11-05T20:00:00Z", 9), zone)
        assertEquals("", sinFecha.fecha)
        assertEquals(15, sinFecha.form.count)
    }

    @Test
    fun `only an existing remedio locks the schedule`() {
        assertTrue(isScheduleLocked(alarm(null, tipo = "remedio")))
        assertFalse(isScheduleLocked(alarm(null, tipo = "otro")))
        assertFalse(isScheduleLocked(alarm(null, tipo = "reunion")))
        assertFalse(isScheduleLocked(null))
    }

    private val hourly15 = buildJsonObject {
        put("freq", "HOURLY")
        put("interval", 8)
        put("count", 15)
    }

    private fun creada(alarmaId: String, hora: String, fecha: String?, at: String) = alarmaCreada(
        "t", Instant.parse(at), alarmaId, "Kalmx Bruno", "remedio", hora, hourly15,
        "Brno", "Kalmx", fecha = fecha, id = "c-$alarmaId",
    )

    private fun params(now: String) = buildJsonObject {
        put("now", now)
        put("tz", "America/La_Paz")
    }

    @Test
    fun `remedio correction resends the stored schedule and keeps P1 and P2`() {
        val before = listOf(
            creada("c1", "08:00", "2026-11-11", "2026-11-10T12:00:00Z"),
            creada("s1", "06:00", null, "2026-11-11T09:00:00Z"),
        )
        val p = params("2026-11-13T11:00:00Z")
        val activasBefore = AlarmasActivas.compute(before, p)
        val adherenciaBefore = AdherenciaTratamiento.compute(before, p)

        val edits = foldAlarms(before).map { a ->
            remedioCorrection("t", Instant.parse("2026-11-12T15:00:00Z"), a, " Kalmix Bruno ", "Bruno", "Kalmix")
        }
        val c1 = edits.single { it.payload["alarma_id"] == JsonPrimitive("c1") }
        val s1 = edits.single { it.payload["alarma_id"] == JsonPrimitive("s1") }
        assertEquals("alarma_modificada", c1.type)
        assertEquals(JsonPrimitive("2026-11-11"), c1.payload["fecha"])
        assertFalse(s1.payload.containsKey("fecha"))
        assertEquals(hourly15, c1.payload["repeticion"])
        assertEquals(JsonPrimitive("08:00"), c1.payload["hora"])
        assertEquals(JsonPrimitive("Kalmix Bruno"), c1.payload["titulo"])

        val after = before + edits
        val activasAfter = AlarmasActivas.compute(after, p)
        assertEquals(
            activasBefore.map { Triple(it.alarmaId, it.proximoDisparo, it.restantes) },
            activasAfter.map { Triple(it.alarmaId, it.proximoDisparo, it.restantes) },
        )
        assertEquals(listOf("2026-11-11", null), activasAfter.map { it.fecha })
        assertEquals(listOf(9, 8), activasAfter.map { it.restantes })
        val adherenciaAfter = AdherenciaTratamiento.compute(after, p)
        assertEquals(adherenciaBefore.map { it.dosisEsperadas }, adherenciaAfter.map { it.dosisEsperadas })
        assertEquals(listOf(6, 7), adherenciaAfter.map { it.dosisEsperadas })
        assertEquals(listOf("Bruno", "Bruno"), adherenciaAfter.map { it.destinatario })
    }

    @Test
    fun `remedio correction refuses a non-remedio alarm`() {
        assertFailsWith<IllegalArgumentException> {
            remedioCorrection("t", ts, alarm(null, tipo = "otro"), "X", "Juan", "Amox")
        }
    }

    @Test
    fun `gcal id detection`() {
        assertTrue(isGcalId("gcal:r1"))
        assertFalse(isGcalId("0198-abc"))
    }

    @Test
    fun `gcal row is found at its scheduled instant and rings`() {
        val events = listOf(reunionEvent())
        val alarm = gcalAlarm(events, "gcal:r1", aviso, zone)
        assertEquals("Revisión", alarm.titulo)
        assertEquals("reunion", alarm.tipo)
        assertTrue(shouldRingGcal(events, "gcal:r1", aviso, zone, snoozed = false))
        assertFalse(shouldRingGcal(emptyList(), "gcal:r1", aviso, zone, snoozed = false))
        assertTrue(shouldRingGcal(emptyList(), "gcal:r1", aviso, zone, snoozed = true))
    }

    @Test
    fun `gcal fire emits only disparada and hecho never desactivada nor dosis`() {
        val events = listOf(reunionEvent())
        val alarm = gcalAlarm(events, "gcal:r1", aviso, zone)
        val fire = fireEvents("d", ts, "gcal:r1", aviso, snoozed = false, alarm = alarm, zone = zone)
        assertEquals(listOf("alarma_disparada"), fire.map { it.type })
        val hecho = hechoEvents("d", ts, "gcal:r1", aviso, alarm)
        assertEquals(listOf("alarma_atendida"), hecho.map { it.type })
        assertNull(hecho.firstOrNull { it.type == "dosis_dada" })
    }

    @Test
    fun `own one-shot remedio hecho still emits dosis and una_vez_atendida`() {
        val hecho = hechoEvents("d", ts, "a1", aviso, alarm(null, tipo = "remedio"))
        assertEquals(listOf("alarma_atendida", "dosis_dada", "alarma_desactivada"), hecho.map { it.type })
    }
}
