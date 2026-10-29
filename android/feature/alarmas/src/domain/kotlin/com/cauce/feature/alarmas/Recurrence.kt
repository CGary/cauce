package com.cauce.feature.alarmas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal enum class Freq { DAILY, WEEKLY, MONTHLY, HOURLY }

internal data class Recurrence(
    val freq: Freq,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: Int? = null,
    val monthOrdinal: Int? = null,
    val monthWeekday: DayOfWeek? = null,
    val count: Int? = null,
    val until: LocalDate? = null,
)

private const val MAX_STEPS = 200_000

private val UTC_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

private val BYDAY = Regex("""^(-1|[1-5])?(MO|TU|WE|TH|FR|SA|SU)$""")

internal fun formatUtc(instant: Instant): String = UTC_FORMAT.format(instant)

internal fun formatLocalHora(instant: Instant, zone: ZoneId): String =
    instant.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun parseRecurrence(element: JsonElement): Recurrence? {
    if (element is JsonNull || element !is JsonObject) return null
    val freq = Freq.valueOf(element.getValue("freq").jsonPrimitive.content)
    val interval = element["interval"]?.jsonPrimitive?.int ?: 1
    require(interval >= 1) { "interval must be >= 1" }
    val count = element["count"]?.jsonPrimitive?.int
    val until = element["until"]?.jsonPrimitive?.content?.let(LocalDate::parse)
    val monthDay = element["bymonthday"]?.jsonPrimitive?.int
    val byday = (element["byday"] as? JsonArray)?.map { parseByday(it.jsonPrimitive.content) }.orEmpty()
    val ordinal = byday.firstOrNull()?.first
    val weekday = byday.firstOrNull()?.second
    return Recurrence(
        freq = freq,
        interval = interval,
        weekdays = byday.map { it.second }.toSet(),
        monthDay = monthDay,
        monthOrdinal = ordinal,
        monthWeekday = weekday,
        count = count,
        until = until,
    )
}

internal fun occurrences(
    createdAt: Instant,
    hora: LocalTime,
    rule: Recurrence?,
    zone: ZoneId,
    fecha: LocalDate? = null,
): Sequence<Instant> {
    val inclusive = fecha != null
    val anchor = if (fecha != null) atHora(fecha, hora, zone) else createdAt
    if (rule == null) {
        return sequenceOf(firstHora(anchor, hora, zone, inclusive))
    }
    require(rule.interval >= 1)
    return when (rule.freq) {
        Freq.DAILY -> daily(anchor, hora, rule, zone, inclusive)
        Freq.WEEKLY -> weekly(anchor, hora, rule, zone, inclusive)
        Freq.MONTHLY -> monthly(anchor, hora, rule, zone, inclusive)
        Freq.HOURLY -> hourly(anchor, hora, rule, zone, inclusive)
    }
}

private fun daily(
    anchor: Instant,
    hora: LocalTime,
    rule: Recurrence,
    zone: ZoneId,
    inclusive: Boolean,
): Sequence<Instant> = sequence {
    val firstDate = firstHora(anchor, hora, zone, inclusive).atZone(zone).toLocalDate()
    var index = 0L
    var emitted = 0
    while (index < MAX_STEPS && !done(rule, emitted)) {
        val date = firstDate.plusDays(index * rule.interval)
        if (pastUntil(date, rule)) break
        yield(atHora(date, hora, zone))
        emitted++
        index++
    }
}

private fun hourly(
    anchor: Instant,
    hora: LocalTime,
    rule: Recurrence,
    zone: ZoneId,
    inclusive: Boolean,
): Sequence<Instant> = sequence {
    val first = firstHora(anchor, hora, zone, inclusive)
    var index = 0L
    var emitted = 0
    while (index < MAX_STEPS && !done(rule, emitted)) {
        val instant = first.plus(index * rule.interval, ChronoUnit.HOURS)
        if (pastUntil(instant.atZone(zone).toLocalDate(), rule)) break
        yield(instant)
        emitted++
        index++
    }
}

private fun weekly(
    anchor: Instant,
    hora: LocalTime,
    rule: Recurrence,
    zone: ZoneId,
    inclusive: Boolean,
): Sequence<Instant> = sequence {
    val derived = rule.weekdays.ifEmpty {
        setOf(firstHora(anchor, hora, zone, inclusive).atZone(zone).dayOfWeek)
    }
    val firstDate = firstMatchingDate(anchor, hora, derived, zone, inclusive) ?: return@sequence
    val anchorMonday = firstDate.with(DayOfWeek.MONDAY)
    var date = firstDate
    var emitted = 0
    var steps = 0
    while (steps < MAX_STEPS && !done(rule, emitted)) {
        if (pastUntil(date, rule)) break
        if (date.dayOfWeek in derived) {
            val weeks = ChronoUnit.WEEKS.between(anchorMonday, date.with(DayOfWeek.MONDAY))
            if (weeks % rule.interval == 0L) {
                val instant = atHora(date, hora, zone)
                if (isAfterAnchor(instant, anchor, inclusive)) {
                    yield(instant)
                    emitted++
                }
            }
        }
        date = date.plusDays(1)
        steps++
    }
}

private fun monthly(
    anchor: Instant,
    hora: LocalTime,
    rule: Recurrence,
    zone: ZoneId,
    inclusive: Boolean,
): Sequence<Instant> = sequence {
    var month = YearMonth.from(anchor.atZone(zone))
    var origin: YearMonth? = null
    var guard = 0
    while (origin == null && guard < 2_400) {
        val occ = occurrenceIn(month, hora, rule, zone)
        if (occ != null && isAfterAnchor(occ, anchor, inclusive)) origin = month else month = month.plusMonths(1)
        guard++
    }
    val anchorMonth = origin ?: return@sequence
    var step = 0L
    var emitted = 0
    while (step < 2_400 && !done(rule, emitted)) {
        val candidate = anchorMonth.plusMonths(step * rule.interval)
        if (rule.until != null && candidate.atDay(1).isAfter(rule.until)) break
        val occ = occurrenceIn(candidate, hora, rule, zone)
        if (occ != null) {
            val date = occ.atZone(zone).toLocalDate()
            if (pastUntil(date, rule)) break
            yield(occ)
            emitted++
        }
        step++
    }
}

private fun occurrenceIn(month: YearMonth, hora: LocalTime, rule: Recurrence, zone: ZoneId): Instant? {
    val date = when {
        rule.monthDay != null -> monthDay(month, rule.monthDay)
        rule.monthOrdinal != null && rule.monthWeekday != null ->
            nthWeekday(month, rule.monthOrdinal, rule.monthWeekday)
        else -> null
    } ?: return null
    return atHora(date, hora, zone)
}

private fun monthDay(month: YearMonth, day: Int): LocalDate? {
    val resolved = if (day == -1) month.lengthOfMonth() else day
    if (resolved !in 1..month.lengthOfMonth()) return null
    return month.atDay(resolved)
}

private fun nthWeekday(month: YearMonth, ordinal: Int, weekday: DayOfWeek): LocalDate? {
    if (ordinal == -1) {
        var date = month.atEndOfMonth()
        while (date.dayOfWeek != weekday) date = date.minusDays(1)
        return date
    }
    if (ordinal !in 1..5) return null
    val first = month.atDay(1)
    val shift = (weekday.value - first.dayOfWeek.value + 7) % 7
    val date = first.plusDays(shift.toLong() + (ordinal - 1) * 7L)
    return date.takeIf { YearMonth.from(it) == month }
}

private fun firstHora(anchor: Instant, hora: LocalTime, zone: ZoneId, inclusive: Boolean): Instant {
    val localDate = anchor.atZone(zone).toLocalDate()
    val sameDay = atHora(localDate, hora, zone)
    return if (isAfterAnchor(sameDay, anchor, inclusive)) {
        sameDay
    } else {
        atHora(localDate.plusDays(1), hora, zone)
    }
}

private fun firstMatchingDate(
    anchor: Instant,
    hora: LocalTime,
    days: Set<DayOfWeek>,
    zone: ZoneId,
    inclusive: Boolean,
): LocalDate? {
    var date = anchor.atZone(zone).toLocalDate()
    repeat(14) {
        if (date.dayOfWeek in days && isAfterAnchor(atHora(date, hora, zone), anchor, inclusive)) return date
        date = date.plusDays(1)
    }
    return null
}

private fun isAfterAnchor(instant: Instant, anchor: Instant, inclusive: Boolean): Boolean =
    if (inclusive) !instant.isBefore(anchor) else instant.isAfter(anchor)

internal fun atHora(date: LocalDate, hora: LocalTime, zone: ZoneId): Instant =
    ZonedDateTime.of(date, hora, zone).toInstant()

private fun done(rule: Recurrence, emitted: Int): Boolean = rule.count != null && emitted >= rule.count

private fun pastUntil(date: LocalDate, rule: Recurrence): Boolean =
    rule.until != null && date.isAfter(rule.until)

private fun parseByday(token: String): Pair<Int?, DayOfWeek> {
    val match = BYDAY.matchEntire(token) ?: error("bad byday: $token")
    val ordinal = match.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()
    return ordinal to weekday(match.groupValues[2])
}

private fun weekday(code: String): DayOfWeek = when (code) {
    "MO" -> DayOfWeek.MONDAY
    "TU" -> DayOfWeek.TUESDAY
    "WE" -> DayOfWeek.WEDNESDAY
    "TH" -> DayOfWeek.THURSDAY
    "FR" -> DayOfWeek.FRIDAY
    "SA" -> DayOfWeek.SATURDAY
    "SU" -> DayOfWeek.SUNDAY
    else -> error("bad weekday: $code")
}
