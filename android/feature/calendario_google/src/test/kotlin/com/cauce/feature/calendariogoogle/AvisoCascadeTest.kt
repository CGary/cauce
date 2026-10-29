package com.cauce.feature.calendariogoogle

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvisoCascadeTest {
    private val zone = ZoneId.of("America/La_Paz")

    private fun reunion(todoElDia: Boolean = false, recordatorio: Int? = null, inicio: String = "2026-11-05T15:00:00Z") =
        ReunionSincronizada(
            reunionId = "r", serieId = "s", cuenta = "c", titulo = "t", inicio = inicio,
            fin = inicio, todoElDia = todoElDia, recordatorioMin = recordatorio,
            estado = "confirmada", respuesta = "aceptada",
        )

    @Test
    fun `edit beats everything`() {
        val (aviso, at) = resolveAviso(reunion(recordatorio = 30), AvisoReunionEditado("s", minutosAntes = 5), zone)
        assertEquals(AvisoVigente("cauce", minutosAntes = 5), aviso)
        assertEquals(Instant.parse("2026-11-05T14:55:00Z"), at)
    }

    @Test
    fun `apagado gives null`() {
        val (aviso, at) = resolveAviso(reunion(), AvisoReunionEditado("s", apagado = true), zone)
        assertEquals(AvisoVigente("cauce", apagado = true), aviso)
        assertNull(at)
    }

    @Test
    fun `all day defaults to 0800 local`() {
        val (aviso, at) = resolveAviso(reunion(todoElDia = true, recordatorio = 1440, inicio = "2026-11-05T00:00:00Z"), null, zone)
        assertEquals(AvisoVigente("defecto", hora = "08:00"), aviso)
        assertEquals(Instant.parse("2026-11-05T12:00:00Z"), at)
    }

    @Test
    fun `google reminder then ten minute default`() {
        val (g, gAt) = resolveAviso(reunion(recordatorio = 0), null, zone)
        assertEquals(AvisoVigente("google", minutosAntes = 0), g)
        assertEquals(Instant.parse("2026-11-05T15:00:00Z"), gAt)
        val (d, dAt) = resolveAviso(reunion(), null, zone)
        assertEquals(AvisoVigente("defecto", minutosAntes = 10), d)
        assertEquals(Instant.parse("2026-11-05T14:50:00Z"), dAt)
    }
}
