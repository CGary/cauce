package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant

class FiringTest {
    private val created: Instant = Instant.parse("2026-10-23T14:00:00Z")

    private fun creada(alarmaId: String = "a1"): Envelope = alarmaCreada(
        "test", created, alarmaId, "Remedio", "remedio", "08:00", JsonNull, "Juan", "Amoxicilina",
    )

    @Test
    fun `una alarma activa timbra`() {
        assertTrue(shouldRing(listOf(creada()), "a1", snoozed = false))
        assertTrue(shouldRing(listOf(creada()), "a1", snoozed = true))
    }

    @Test
    fun `una alarma inexistente no timbra`() {
        assertFalse(shouldRing(emptyList(), "a1", snoozed = false))
        assertFalse(shouldRing(emptyList(), "a1", snoozed = true))
    }

    @Test
    fun `desactivada por el usuario cancela el disparo y la posposicion`() {
        val events = listOf(
            creada(),
            alarmaDesactivada("test", created.plusSeconds(60), "a1", "usuario"),
        )
        assertFalse(shouldRing(events, "a1", snoozed = false))
        assertFalse(shouldRing(events, "a1", snoozed = true))
    }

    @Test
    fun `fin de tratamiento no cancela la posposicion`() {
        val events = listOf(
            creada(),
            alarmaDesactivada("test", created.plusSeconds(60), "a1", "fin_tratamiento"),
        )
        assertFalse(shouldRing(events, "a1", snoozed = false))
        assertTrue(shouldRing(events, "a1", snoozed = true))
    }

    @Test
    fun `el ultimo motivo de desactivacion es el que decide`() {
        val events = listOf(
            creada(),
            alarmaDesactivada("test", created.plusSeconds(60), "a1", "fin_tratamiento"),
            alarmaDesactivada("test", created.plusSeconds(120), "a1", "usuario"),
        )
        assertEquals("usuario", lastDeactivationMotivo(events, "a1"))
        assertFalse(shouldRing(events, "a1", snoozed = true))
    }
}
