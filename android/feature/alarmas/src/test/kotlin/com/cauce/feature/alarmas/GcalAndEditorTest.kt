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

    private fun alarm(rep: JsonObject?, hora: String = "08:00", fecha: String? = null) = Alarm(
        id = "a1", createdAt = ts, titulo = "X", tipo = "remedio",
        hora = java.time.LocalTime.parse(hora), horaRaw = hora,
        repeticion = rep ?: JsonNull, rule = parseRecurrence(rep ?: JsonNull),
        destinatario = "Juan", remedio = "Amox", fecha = fecha?.let(java.time.LocalDate::parse),
        deactivated = false,
    )

    private fun row(next: String, restantes: Int? = null) = AlarmaActiva(
        "a1", "X", "remedio", "08:00", JsonNull, next, restantes,
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
        val hecho = hechoEvents("d", ts, "a1", aviso, alarm(null))
        assertEquals(listOf("alarma_atendida", "dosis_dada", "alarma_desactivada"), hecho.map { it.type })
    }
}
