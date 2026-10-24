package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant

class SnoozeTest {
    private val ts: Instant = Instant.parse("2026-10-23T14:00:00Z")
    private val pp: Instant = Instant.parse("2026-10-24T08:00:00Z")
    private val ppRaw = "2026-10-24T08:00:00Z"

    private fun pospuesta(n: Int, alarmaId: String = "a1", at: Instant = pp): Envelope =
        alarmaAtendida("test", ts, alarmaId, at, "pospuesta", pospuestaN = n)

    @Test
    fun `sin posposiciones la primera es la 1`() {
        assertEquals(SnoozeOutcome.Pospuesta(1), nextSnoozeOutcome(emptyList(), "a1", ppRaw))
    }

    @Test
    fun `cuenta solo las pospuestas de esta ocurrencia`() {
        val events = listOf(
            pospuesta(1),
            pospuesta(1, alarmaId = "otra"),
            pospuesta(1, at = Instant.parse("2026-10-25T08:00:00Z")),
            alarmaAtendida("test", ts, "a1", pp, "hecho"),
            alarmaDisparada("test", ts, "a1", pp),
        )
        assertEquals(SnoozeOutcome.Pospuesta(2), nextSnoozeOutcome(events, "a1", ppRaw))
    }

    @Test
    fun `se puede posponer hasta 3 veces, a la cuarta es ignorada`() {
        val after2 = listOf(pospuesta(1), pospuesta(2))
        assertEquals(SnoozeOutcome.Pospuesta(3), nextSnoozeOutcome(after2, "a1", ppRaw))

        val after3 = after2 + pospuesta(3)
        assertEquals(SnoozeOutcome.Ignorada, nextSnoozeOutcome(after3, "a1", ppRaw))
    }

    @Test
    fun `hasAtendida solo reconoce las acciones terminales de la ocurrencia`() {
        assertFalse(hasAtendida(emptyList(), "a1", ppRaw))
        assertFalse(hasAtendida(listOf(alarmaDisparada("test", ts, "a1", pp)), "a1", ppRaw))
        assertFalse(hasAtendida(listOf(pospuesta(1)), "a1", ppRaw))
        assertTrue(
            hasAtendida(listOf(alarmaAtendida("test", ts, "a1", pp, "ignorada")), "a1", ppRaw),
        )
        assertTrue(
            hasAtendida(listOf(alarmaAtendida("test", ts, "a1", pp, "hecho")), "a1", ppRaw),
        )
    }

    @Test
    fun `hasAtendida ignora otra ocurrencia y otra alarma`() {
        val events = listOf(
            alarmaAtendida("test", ts, "a1", Instant.parse("2026-10-25T08:00:00Z"), "ignorada"),
            alarmaAtendida("test", ts, "otra", pp, "ignorada"),
        )
        assertFalse(hasAtendida(events, "a1", ppRaw))
    }

    @Test
    fun `tras posponer el timeout sigue abierto y cuenta la ignorada`() {
        val events = listOf(pospuesta(1))
        assertFalse(hasAtendida(events, "a1", ppRaw))
        assertFalse(hasAtendida(events + pospuesta(2), "a1", ppRaw))
        assertFalse(hasAtendida(events + pospuesta(3), "a1", ppRaw))
    }
}
