package com.cauce.feature.alarmas

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FECHA_ES: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale("es"))

/** Localized display of a stored `YYYY-MM-DD` date, e.g. "dom 1 nov 2026". Unparseable input is returned as is. */
internal fun formatFechaEs(iso: String): String = try {
    FECHA_ES.format(LocalDate.parse(iso)).replace(".", "")
} catch (_: Exception) {
    iso
}

/** Parses the stored `HH:MM` value; null when it is not a valid time. */
internal fun parseHora(hora: String): LocalTime? = runCatching {
    LocalTime.parse(hora.trim(), DateTimeFormatter.ofPattern("HH:mm"))
}.getOrNull()

/** Stored `HH:MM` value for a picker result. */
internal fun formatHora(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
