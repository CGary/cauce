package com.cauce.feature.alarmas

import com.cauce.core.events.CauceJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonNull

class RepeticionFormTest {
    private fun assertJson(expected: String, actual: kotlinx.serialization.json.JsonElement) {
        assertEquals(CauceJson.wire.parseToJsonElement(expected), actual)
    }

    @Test
    fun `sin repeticion es null (una sola vez)`() {
        assertEquals(JsonNull, RepeticionForm().toJson())
    }

    @Test
    fun `cada 8 h por 5 dias es HOURLY interval 8 count 15`() {
        assertJson(
            """{ "freq": "HOURLY", "interval": 8, "count": 15 }""",
            RepeticionForm(freq = "HOURLY", interval = 8, count = 15).toJson(),
        )
    }

    @Test
    fun `semanal con dias ordena MO primero y omite interval 1`() {
        assertJson(
            """{ "freq": "WEEKLY", "byday": ["MO", "WE"] }""",
            RepeticionForm(freq = "WEEKLY", weeklyDays = setOf("WE", "MO")).toJson(),
        )
    }

    @Test
    fun `mensual modo dia del mes, incluido ultimo dia`() {
        assertJson(
            """{ "freq": "MONTHLY", "bymonthday": 15 }""",
            RepeticionForm(freq = "MONTHLY", monthlyDay = 15).toJson(),
        )
        assertJson(
            """{ "freq": "MONTHLY", "bymonthday": -1 }""",
            RepeticionForm(freq = "MONTHLY", monthlyDay = -1).toJson(),
        )
    }

    @Test
    fun `mensual modo dia de la semana con ordinal`() {
        assertJson(
            """{ "freq": "MONTHLY", "interval": 2, "byday": ["1MO"] }""",
            RepeticionForm(
                freq = "MONTHLY",
                interval = 2,
                monthlyModeIsOrdinal = true,
                monthlyOrdinal = 1,
                monthlyWeekday = "MO",
            ).toJson(),
        )
        assertJson(
            """{ "freq": "MONTHLY", "byday": ["-1FR"] }""",
            RepeticionForm(
                freq = "MONTHLY",
                monthlyModeIsOrdinal = true,
                monthlyOrdinal = -1,
                monthlyWeekday = "FR",
            ).toJson(),
        )
    }

    @Test
    fun `count y until son excluyentes y until es una fecha`() {
        assertFailsWith<IllegalArgumentException> {
            RepeticionForm(freq = "DAILY", count = 5, until = "2026-11-01").toJson()
        }
        assertFailsWith<IllegalArgumentException> {
            RepeticionForm(freq = "DAILY", until = "01/11/2026").toJson()
        }
        assertJson(
            """{ "freq": "DAILY", "until": "2026-11-01" }""",
            RepeticionForm(freq = "DAILY", until = "2026-11-01").toJson(),
        )
    }

    @Test
    fun `roundtrip prefill del editor`() {
        val forms = listOf(
            RepeticionForm(),
            RepeticionForm(freq = "DAILY", interval = 3, until = "2026-12-31"),
            RepeticionForm(freq = "WEEKLY", interval = 2, weeklyDays = setOf("FR")),
            RepeticionForm(freq = "MONTHLY", monthlyDay = 31, count = 3),
            RepeticionForm(freq = "MONTHLY", monthlyDay = -1),
            RepeticionForm(
                freq = "MONTHLY",
                monthlyModeIsOrdinal = true,
                monthlyOrdinal = -1,
                monthlyWeekday = "FR",
                interval = 2,
            ),
            RepeticionForm(freq = "HOURLY", interval = 8, count = 15),
        )
        forms.forEach { form ->
            assertEquals(form, repeticionFormFromJson(form.toJson()), "roundtrip de $form")
        }
    }

    @Test
    fun `describe resume la regla en espanol`() {
        assertEquals("Una vez", RepeticionForm().describe())
        assertEquals("Cada 8 h · 15 disparos", RepeticionForm(freq = "HOURLY", interval = 8, count = 15).describe())
        assertEquals(
            "Cada mes: último día del mes",
            RepeticionForm(freq = "MONTHLY", monthlyDay = -1).describe(),
        )
        assertEquals(
            "Cada mes: 1.º lunes",
            RepeticionForm(
                freq = "MONTHLY",
                monthlyModeIsOrdinal = true,
                monthlyOrdinal = 1,
                monthlyWeekday = "MO",
            ).describe(),
        )
    }
}
