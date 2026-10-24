package com.cauce.feature.alarmas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The editor's view of `repeticion` (SPEC §2.1 and the `repeticion` schema). Pure so the form
 * validation the UI does is the same one the JVM tests pin down: MONTHLY has exactly two modes
 * ("día N del mes" with `bymonthday`, or "N-ésimo día de la semana" with one `byday` ordinal),
 * `count` and `until` are mutually exclusive.
 */
internal data class RepeticionForm(
    /** `null` = una sola vez; otherwise DAILY, WEEKLY, MONTHLY or HOURLY. */
    val freq: String? = null,
    val interval: Int = 1,
    /** WEEKLY days (MO..SU), possibly empty (RFC 5545 default: the first firing's weekday). */
    val weeklyDays: Set<String> = emptySet(),
    /** MONTHLY mode "día del mes": 1..31 or -1 (último día). */
    val monthlyDay: Int = 1,
    /** MONTHLY mode switch: false = día del mes, true = día de la semana con ordinal. */
    val monthlyModeIsOrdinal: Boolean = false,
    /** MONTHLY ordinal: 1..5 (n-ésimo) or -1 (último). */
    val monthlyOrdinal: Int = 1,
    /** MONTHLY weekday for the ordinal mode (MO..SU). */
    val monthlyWeekday: String = "MO",
    val count: Int? = null,
    /** Inclusive last local date, YYYY-MM-DD. Mutually exclusive with [count]. */
    val until: String? = null,
)

internal val WEEKDAY_CODES: List<String> = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

private val WEEKDAY_LABELS = mapOf(
    "MO" to "lunes",
    "TU" to "martes",
    "WE" to "miércoles",
    "TH" to "jueves",
    "FR" to "viernes",
    "SA" to "sábado",
    "SU" to "domingo",
)

private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val ORDINAL_BYDAY = Regex("""^(-1|[1-5])(MO|TU|WE|TH|FR|SA|SU)$""")

/** Serializes to the exact `repeticion` JSON of `alarma_creada.v2` / `alarma_modificada.v2`. */
internal fun RepeticionForm.toJson(): JsonElement {
    val f = freq ?: return JsonNull
    require(f in setOf("DAILY", "WEEKLY", "MONTHLY", "HOURLY")) { "freq inválida: $f" }
    require(interval >= 1) { "interval debe ser >= 1" }
    require(count == null || until == null) { "count y until son excluyentes" }
    require(count == null || count >= 1) { "count debe ser >= 1" }
    if (until != null) require(DATE.matches(until)) { "until debe ser YYYY-MM-DD" }

    val fields = mutableMapOf<String, JsonElement>("freq" to JsonPrimitive(f))
    if (interval != 1) fields["interval"] = JsonPrimitive(interval)
    when (f) {
        "WEEKLY" -> if (weeklyDays.isNotEmpty()) {
            fields["byday"] = JsonArray(
                weeklyDays.sortedBy(WEEKDAY_CODES::indexOf).map { JsonPrimitive(it) },
            )
        }
        "MONTHLY" -> if (monthlyModeIsOrdinal) {
            require(monthlyOrdinal == -1 || monthlyOrdinal in 1..5) {
                "ordinal mensual debe ser 1..5 o -1"
            }
            require(monthlyWeekday in WEEKDAY_CODES) { "día inválido: $monthlyWeekday" }
            fields["byday"] = JsonArray(listOf(JsonPrimitive("$monthlyOrdinal$monthlyWeekday")))
        } else {
            require(monthlyDay == -1 || monthlyDay in 1..31) { "día del mes debe ser 1..31 o -1" }
            fields["bymonthday"] = JsonPrimitive(monthlyDay)
        }
    }
    if (count != null) fields["count"] = JsonPrimitive(count)
    if (until != null) fields["until"] = JsonPrimitive(until)
    return JsonObject(fields)
}

/** Inverse of [toJson], used to prefill the editor with the current state of an alarm. */
internal fun repeticionFormFromJson(element: JsonElement): RepeticionForm {
    if (element !is JsonObject) return RepeticionForm()
    val freq = element["freq"]?.jsonPrimitive?.contentOrNull ?: return RepeticionForm()
    val byday = (element["byday"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    val ordinal = if (freq == "MONTHLY") {
        byday.firstOrNull()?.let { ORDINAL_BYDAY.matchEntire(it) }
            ?.let { it.groupValues[1].toInt() to it.groupValues[2] }
    } else {
        null
    }
    return RepeticionForm(
        freq = freq,
        interval = element["interval"]?.jsonPrimitive?.int ?: 1,
        weeklyDays = if (freq == "WEEKLY") byday.toSet() else emptySet(),
        monthlyDay = element["bymonthday"]?.jsonPrimitive?.int ?: 1,
        monthlyModeIsOrdinal = ordinal != null,
        monthlyOrdinal = ordinal?.first ?: 1,
        monthlyWeekday = ordinal?.second ?: "MO",
        count = element["count"]?.jsonPrimitive?.int,
        until = element["until"]?.jsonPrimitive?.contentOrNull,
    )
}

/** Short Spanish label for the list row, e.g. "cada 8 h · 15 disparos". */
internal fun RepeticionForm.describe(): String {
    val f = freq ?: return "Una vez"
    val every = when (f) {
        "DAILY" -> if (interval == 1) "Cada día" else "Cada $interval días"
        "WEEKLY" -> {
            val days = weeklyDays.sortedBy(WEEKDAY_CODES::indexOf)
                .joinToString(", ") { WEEKDAY_LABELS.getValue(it) }
            val base = if (interval == 1) "Cada semana" else "Cada $interval semanas"
            if (days.isEmpty()) base else "$base: $days"
        }
        "MONTHLY" -> {
            val base = if (interval == 1) "Cada mes" else "Cada $interval meses"
            val rule = if (monthlyModeIsOrdinal) {
                val ordinal = if (monthlyOrdinal == -1) "último" else "$monthlyOrdinal.º"
                "$ordinal ${WEEKDAY_LABELS.getValue(monthlyWeekday)}"
            } else {
                if (monthlyDay == -1) "último día del mes" else "día $monthlyDay"
            }
            "$base: $rule"
        }
        "HOURLY" -> if (interval == 1) "Cada hora" else "Cada $interval h"
        else -> f
    }
    val limit = when {
        count != null -> "$count disparos"
        until != null -> "hasta $until"
        else -> null
    }
    return listOfNotNull(every, limit).joinToString(" · ")
}
