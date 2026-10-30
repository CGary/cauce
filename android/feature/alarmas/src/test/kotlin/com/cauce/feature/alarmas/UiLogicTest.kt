package com.cauce.feature.alarmas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UiLogicTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `relativeAgo cubre nunca minutos horas y dias`() {
        assertEquals("nunca", relativeAgo(now, null))
        assertEquals("hace un momento", relativeAgo(now, now.minusSeconds(20)))
        assertEquals("hace 5 min", relativeAgo(now, now.minusSeconds(5 * 60)))
        assertEquals("hace 3 h", relativeAgo(now, now.minusSeconds(3 * 3600 + 10)))
        assertEquals("hace 2 d", relativeAgo(now, now.minusSeconds(2 * 86400)))
    }

    @Test
    fun `gcalHeadline muestra inicio y aviso`() {
        val inicio = Instant.parse("2026-10-30T17:00:00Z")
        val aviso = Instant.parse("2026-10-30T16:50:00Z")
        assertEquals("Reunión 17:00 · aviso 16:50", gcalHeadline(inicio, false, aviso, utc))
        assertEquals(
            "Reunión todo el día · aviso 08:00",
            gcalHeadline(inicio, true, Instant.parse("2026-10-30T08:00:00Z"), utc),
        )
    }

    private fun row(next: String) = AlarmaActiva(
        alarmaId = "a", titulo = "t", tipo = "otro", hora = "17:00",
        repeticion = kotlinx.serialization.json.JsonNull, proximoDisparo = next,
    )

    @Test
    fun `nextAlarmLabel hoy manana y vacio`() {
        assertNull(nextAlarmLabel(emptyList(), now, utc))
        assertEquals("Próxima: 17:00 hoy", nextAlarmLabel(listOf(row("2026-10-30T17:00:00Z")), now, utc))
        assertEquals(
            "Próxima: 09:00 mañana",
            nextAlarmLabel(listOf(row("2026-10-31T09:00:00Z"), row("2026-11-02T09:00:00Z")), now, utc),
        )
    }

    @Test
    fun `groupHistorialByDay ordena los dias de nuevo a viejo`() {
        val e = { ts: String -> HistorialDosisEntry(ts, "Ana", "X") }
        val groups = groupHistorialByDay(
            listOf(e("2026-10-30T10:00:00Z"), e("2026-10-30T02:00:00Z"), e("2026-10-29T22:00:00Z")),
            utc,
        )
        assertEquals(listOf(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-29")), groups.map { it.first })
        assertEquals(2, groups[0].second.size)
    }

    @Test
    fun `adherenciaPercent`() {
        assertNull(adherenciaPercent(0, 0))
        assertEquals(87, adherenciaPercent(13, 15))
    }
}
