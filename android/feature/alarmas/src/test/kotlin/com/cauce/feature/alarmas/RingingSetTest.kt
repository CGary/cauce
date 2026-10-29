package com.cauce.feature.alarmas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant

class RingingSetTest {
    private val ts: Instant = Instant.parse("2026-10-24T08:00:00Z")
    private val pp: Instant = Instant.parse("2026-10-24T08:00:00Z")
    private val a = RingingKey("a1", "2026-10-24T08:00:00Z")
    private val b = RingingKey("a2", "2026-10-24T08:00:00Z")

    @Test
    fun `empty set does not ring`() {
        assertFalse(RingingSet().isRinging)
    }

    @Test
    fun `rings while any firing is open and stops with the last`() {
        val set = RingingSet()
        set.start(a)
        set.start(b)
        assertTrue(set.isRinging)
        assertFalse(set.close(a))
        assertTrue(set.isRinging)
        assertTrue(set.close(b))
        assertFalse(set.isRinging)
    }

    @Test
    fun `closing an unknown firing does not stop the others`() {
        val set = RingingSet()
        set.start(a)
        assertFalse(set.close(b))
        assertTrue(set.isRinging)
    }

    @Test
    fun `starting the same occurrence twice counts once`() {
        val set = RingingSet()
        set.start(a)
        set.start(a)
        assertTrue(set.close(a))
    }

    @Test
    fun `terminal atendida closes, pospuesta keeps ringing`() {
        val set = RingingSet()
        set.start(a)
        set.start(b)
        val events = listOf(
            alarmaAtendida("test", ts, "a1", pp, "hecho"),
            alarmaAtendida("test", ts, "a2", pp, "pospuesta", pospuestaN = 1),
        )
        assertEquals(listOf(a), set.closedIn(events))
    }
}
