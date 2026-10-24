package com.cauce.feature.alarmas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

class SchedulePlanTest {
    private val now: Instant = Instant.parse("2026-10-23T14:00:00Z")

    private fun activa(id: String, proximoDisparo: String) = AlarmaActiva(
        alarmaId = id,
        titulo = "t-$id",
        tipo = "otro",
        hora = "08:00",
        repeticion = JsonNull,
        proximoDisparo = proximoDisparo,
    )

    @Test
    fun `una alarma programable por alarma activa, ordenada por disparo`() {
        val plans = planFirings(
            listOf(
                activa("b", "2026-10-25T08:00:00Z"),
                activa("a", "2026-10-24T08:00:00Z"),
            ),
            now,
        )
        assertEquals(listOf("a", "b"), plans.map { it.alarmaId })
        assertEquals(Instant.parse("2026-10-24T08:00:00Z"), plans.first().triggerAt)
    }

    @Test
    fun `descarta disparos que no son futuros (defensa ante P1 vieja)`() {
        val plans = planFirings(
            listOf(
                activa("pasada", "2026-10-23T08:00:00Z"),
                activa("justa", "2026-10-23T14:00:00Z"),
                activa("futura", "2026-10-23T14:00:01Z"),
            ),
            now,
        )
        assertEquals(listOf("futura"), plans.map { it.alarmaId })
    }

    @Test
    fun `params de proyeccion llevan now en UTC y tz IANA`() {
        val params = projectionParams(now, ZoneId.of("America/La_Paz"))
        assertEquals("2026-10-23T14:00:00Z", params.getValue("now").jsonPrimitive.content)
        assertEquals("America/La_Paz", params.getValue("tz").jsonPrimitive.content)
    }
}
