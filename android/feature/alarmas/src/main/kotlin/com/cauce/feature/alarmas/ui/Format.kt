package com.cauce.feature.alarmas.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UI_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale("es"))

internal fun formatInstantForUi(iso: String): String = try {
    UI_FORMAT.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (_: Exception) {
    iso
}
