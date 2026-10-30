package com.cauce.feature.alarmas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EditorFormatTest {
    @Test
    fun fechaIsLocalized() {
        assertEquals("dom 1 nov 2026", formatFechaEs("2026-11-01"))
        assertEquals("basura", formatFechaEs("basura"))
    }

    @Test
    fun horaRoundTrips() {
        assertEquals("08:05", formatHora(8, 5))
        assertEquals(8, parseHora("08:05")?.hour)
        assertNull(parseHora("25:00"))
    }

    @Test
    fun hourlyProducesSpecShape() {
        val json = RepeticionForm(freq = "HOURLY", interval = 8, count = 15).toJson()
        assertEquals(
            JsonObject(
                mapOf(
                    "freq" to JsonPrimitive("HOURLY"),
                    "interval" to JsonPrimitive(8),
                    "count" to JsonPrimitive(15),
                ),
            ),
            json,
        )
        assertFailsWith<IllegalArgumentException> { RepeticionForm(freq = "HOURLY", interval = 0).toJson() }
    }
}
