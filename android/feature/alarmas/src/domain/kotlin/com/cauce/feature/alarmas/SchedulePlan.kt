package com.cauce.feature.alarmas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/** What the device layer programs into `AlarmManager`: one exact alarm per active P1 row. */
internal data class FiringPlan(
    val alarmaId: String,
    val triggerAt: Instant,
)

/**
 * P1 already computed `proximo_disparo` strictly after `now`; the filter is a defensive re-check
 * so a stale projection result can never schedule an alarm in the past.
 */
internal fun planFirings(activas: List<AlarmaActiva>, now: Instant): List<FiringPlan> =
    activas
        .map { FiringPlan(alarmaId = it.alarmaId, triggerAt = Instant.parse(it.proximoDisparo)) }
        .filter { it.triggerAt.isAfter(now) }
        .sortedWith(compareBy({ it.triggerAt }, { it.alarmaId }))

/** The `params` every P1/P2 call takes: time is an input, never read inside a projection. */
internal fun projectionParams(now: Instant, zone: ZoneId): JsonObject = JsonObject(
    mapOf(
        "now" to JsonPrimitive(formatUtc(now)),
        "tz" to JsonPrimitive(zone.id),
    ),
)
