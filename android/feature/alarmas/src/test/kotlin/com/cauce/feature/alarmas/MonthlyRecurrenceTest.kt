package com.cauce.feature.alarmas

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MonthlyRecurrenceTest {
    private val zone = ZoneId.of("America/La_Paz")
    private val hora = LocalTime.parse("09:00")

    @Test
    fun `bymonthday 31 skips 30-day and short months and does not consume count`() {
        val rule = Recurrence(freq = Freq.MONTHLY, monthDay = 31, count = 5)
        assertEquals(
            listOf(
                "2026-10-31T13:00:00Z",
                "2026-12-31T13:00:00Z",
                "2027-01-31T13:00:00Z",
                "2027-03-31T13:00:00Z",
                "2027-05-31T13:00:00Z",
            ),
            fire("2026-10-23T14:00:00Z", rule),
        )
    }

    @Test
    fun `bymonthday 31 on the creation day after hora starts at the next existing 31`() {
        val rule = Recurrence(freq = Freq.MONTHLY, monthDay = 31, count = 2)
        assertEquals(
            listOf(
                "2026-12-31T13:00:00Z",
                "2027-01-31T13:00:00Z",
            ),
            fire("2026-10-31T14:00:00Z", rule),
        )
    }

    @Test
    fun `bymonthday -1 is the last day including February leap and non-leap`() {
        val rule = Recurrence(freq = Freq.MONTHLY, monthDay = -1, count = 5)
        assertEquals(
            listOf(
                "2027-01-31T13:00:00Z",
                "2027-02-28T13:00:00Z",
                "2027-03-31T13:00:00Z",
                "2028-01-31T13:00:00Z",
                "2028-02-29T13:00:00Z",
            ),
            fire("2027-01-10T12:00:00Z", rule.copy(interval = 1, count = 3)) +
                fire("2028-01-10T12:00:00Z", rule.copy(count = 2)),
        )
    }

    @Test
    fun `5th weekday missing in a month is skipped and does not consume count`() {
        val rule = Recurrence(
            freq = Freq.MONTHLY,
            monthOrdinal = 5,
            monthWeekday = DayOfWeek.MONDAY,
            count = 3,
        )
        assertEquals(
            listOf(
                "2026-11-30T13:00:00Z",
                "2027-03-29T13:00:00Z",
                "2027-05-31T13:00:00Z",
            ),
            fire("2026-10-01T12:00:00Z", rule),
        )
        assertEquals(
            "2027-08-30T13:00:00Z",
            fire("2026-10-01T12:00:00Z", rule.copy(count = 4)).last(),
        )
    }

    @Test
    fun `interval 2 anchors on the first firing and a missing day does not consume count`() {
        val ordinal = Recurrence(
            freq = Freq.MONTHLY,
            interval = 2,
            monthOrdinal = -1,
            monthWeekday = DayOfWeek.FRIDAY,
            count = 3,
        )
        assertEquals(
            listOf(
                "2026-10-30T22:00:00Z",
                "2026-12-25T22:00:00Z",
                "2027-02-26T22:00:00Z",
            ),
            fire("2026-10-23T14:00:00Z", ordinal, hora = "18:00"),
        )

        val thirtyFirst = Recurrence(freq = Freq.MONTHLY, interval = 2, monthDay = 31, count = 3)
        assertEquals(
            listOf(
                "2026-10-31T13:00:00Z",
                "2026-12-31T13:00:00Z",
                "2027-08-31T13:00:00Z",
            ),
            fire("2026-10-23T14:00:00Z", thirtyFirst),
        )

        val anchoredLater = Recurrence(freq = Freq.MONTHLY, interval = 2, monthDay = 15, count = 3)
        assertEquals(
            listOf(
                "2026-12-15T13:00:00Z",
                "2027-02-15T13:00:00Z",
                "2027-04-15T13:00:00Z",
            ),
            fire("2026-11-20T15:00:00Z", anchoredLater),
        )
    }

    private fun fire(created: String, rule: Recurrence, hora: String = "09:00"): List<String> =
        occurrences(Instant.parse(created), LocalTime.parse(hora), rule, zone).map(::formatUtc).toList()
}