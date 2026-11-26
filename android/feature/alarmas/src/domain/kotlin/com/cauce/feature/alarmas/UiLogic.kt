package com.cauce.feature.alarmas

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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

/** "1 tratamiento" / "2 tratamientos": [n] with the singular or plural noun. */
internal fun countNoun(n: Int, singular: String, plural: String): String =
    "$n ${if (n == 1) singular else plural}"

/** Distinct recipients for the Historial dropdown, sorted. */
internal fun distinctDestinatarios(entries: List<HistorialDosisEntry>): List<String> =
    entries.map { it.destinatario }.filter { it.isNotBlank() }.distinct().sorted()

/** Percentage of given doses over expected ones; null when nothing was expected yet. */
internal fun adherenciaPercent(dadas: Int, esperadas: Int): Int? =
    if (esperadas <= 0) null else Math.round(dadas * 100.0 / esperadas).toInt()
