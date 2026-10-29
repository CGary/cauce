package com.cauce.feature.calendariogoogle

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncDiffTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun reunion(id: String, inicio: String, titulo: String = "Standup") = ReunionSincronizada(
        reunionId = id, serieId = id.substringBefore(':'), cuenta = "a@example.com", calendario = "Trabajo",
        titulo = titulo, inicio = inicio, fin = inicio, todoElDia = false, recordatorioMin = 10,
        estado = "confirmada", respuesta = "aceptada",
    )

    private val futura = reunion("1:100", "2026-10-30T09:00:00Z")

    @Test fun nuevaEmiteSincronizada() {
        assertEquals(listOf(SyncAction.Sincronizar(futura)), syncDiff(emptyMap(), listOf(futura), now))
    }

    @Test fun sinCambiosNoEmiteNada() {
        assertTrue(syncDiff(mapOf(futura.reunionId to futura), listOf(futura), now).isEmpty())
    }

    @Test fun campoCambiadoEmiteSincronizada() {
        val cambiada = futura.copy(titulo = "Otro")
        assertEquals(
            listOf(SyncAction.Sincronizar(cambiada)),
            syncDiff(mapOf(futura.reunionId to futura), listOf(cambiada), now),
        )
    }

    @Test fun desaparecidaFuturaEmiteEliminada() {
        val otra = reunion("2:200", "2026-10-31T09:00:00Z")
        assertEquals(
            listOf(SyncAction.Eliminar("1:100")),
            syncDiff(mapOf("1:100" to futura, "2:200" to otra), listOf(otra), now),
        )
    }

    @Test fun desaparecidaPasadaNoEmiteNada() {
        val pasada = reunion("3:300", "2026-10-29T08:00:00Z")
        val otra = reunion("2:200", "2026-10-31T09:00:00Z")
        assertTrue(syncDiff(mapOf("3:300" to pasada, "2:200" to otra), listOf(otra), now).isEmpty())
    }

    @Test fun lecturaNulaOVaciaNoEmiteNada() {
        val prev = mapOf(futura.reunionId to futura)
        assertTrue(syncDiff(prev, null, now).isEmpty())
        assertTrue(syncDiff(prev, emptyList(), now).isEmpty())
    }

    @Test fun avisoEditadoLlevaExactamenteUnaClave() {
        val ts = now
        assertEquals(setOf("serie_id", "minutos_antes"), avisoReunionEditadoEvent("d", ts, "1", minutosAntes = 0).payload.keys)
        assertEquals(setOf("serie_id", "hora"), avisoReunionEditadoEvent("d", ts, "1", hora = "07:30").payload.keys)
        assertEquals(setOf("serie_id", "apagado"), avisoReunionEditadoEvent("d", ts, "1", apagado = true).payload.keys)
        assertFailsWith<IllegalArgumentException> { avisoReunionEditadoEvent("d", ts, "1") }
        assertFailsWith<IllegalArgumentException> { avisoReunionEditadoEvent("d", ts, "1", minutosAntes = 5, apagado = true) }
        assertFailsWith<IllegalArgumentException> { avisoReunionEditadoEvent("d", ts, "1", minutosAntes = -1) }
        assertFailsWith<IllegalArgumentException> { avisoReunionEditadoEvent("d", ts, "1", hora = "25:00") }
    }

    @Test fun sincronizadaRoundTripConElFold() {
        val ev = reunionSincronizadaEvent("d", now, futura)
        assertEquals(mapOf(futura.reunionId to futura), previousState(listOf(ev)))
        val del = reunionEliminadaEvent("d", now.plusSeconds(1), futura.reunionId)
        assertTrue(previousState(listOf(ev, del)).isEmpty())
    }
}
