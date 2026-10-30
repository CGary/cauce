package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "hace 5 min" / "nunca": the relative age of [then] as seen at [now]. */
fun relativeAgo(now: Instant, then: Instant?): String {
    if (then == null) return "nunca"
    val minutes = Duration.between(then, now).toMinutes()
    return when {
        minutes < 1 -> "hace un momento"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / (60 * 24)} d"
    }
}

/** The newest event `ts` in the local store: the proxy for "last sync" (core exposes none). */
fun newestTs(events: List<Envelope>): Instant? =
    events.maxOfOrNull { Instant.parse(it.ts) }

/** Same wording as the Reuniones screen: "Reunión 17:00 · aviso 16:50". */
internal fun gcalHeadline(inicio: Instant, todoElDia: Boolean, aviso: Instant, zone: ZoneId): String {
    val start = if (todoElDia) "todo el día" else inicio.atZone(zone).format(HM)
    return "Reunión $start · aviso ${aviso.atZone(zone).format(HM)}"
}

/** "Próxima: 17:00 hoy" / "mañana" / "dd/MM"; null when there is no upcoming alarm. */
internal fun nextAlarmLabel(rows: List<AlarmaActiva>, now: Instant, zone: ZoneId): String? {
    val next = rows.minByOrNull { Instant.parse(it.proximoDisparo) } ?: return null
    val at = Instant.parse(next.proximoDisparo).atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val day = when (at.toLocalDate()) {
        today -> "hoy"
        today.plusDays(1) -> "mañana"
        else -> at.format(DateTimeFormatter.ofPattern("d/M"))
    }
    return "Próxima: ${at.format(HM)} $day"
}

/** Dose history grouped by local day, newest day first (entries keep P3's order inside a day). */
internal fun groupHistorialByDay(
    entries: List<HistorialDosisEntry>,
    zone: ZoneId,
): List<Pair<LocalDate, List<HistorialDosisEntry>>> =
    entries.groupBy { Instant.parse(it.ts).atZone(zone).toLocalDate() }
        .toList()
        .sortedByDescending { it.first }

/** Percentage of given doses over expected ones; null when nothing was expected yet. */
internal fun adherenciaPercent(dadas: Int, esperadas: Int): Int? =
    if (esperadas <= 0) null else Math.round(dadas * 100.0 / esperadas).toInt()
