package com.cauce.feature.alarmas

import com.cauce.core.events.Envelope
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeoutTest {
    private val pp: Instant = Instant.parse("2026-11-26T08:00:00Z")
    private val ppRaw = "2026-11-26T08:00:00Z"
    private val t: Instant = Instant.parse("2026-11-26T08:00:01Z")

    private fun disparada(at: Instant, alarmaId: String = "a1", para: Instant = pp): Envelope =
        alarmaDisparada("test", at, alarmaId, para)

    private data class Case(
        val name: String,
        val events: List<Envelope>,
        val now: Instant,
        val expected: Duration?,
    )

    private val cases = listOf(
        Case("5 s despues del disparo faltan 115 s", listOf(disparada(t)), t.plusSeconds(5), Duration.ofSeconds(115)),
        Case("a los 120 s corresponde", listOf(disparada(t)), t.plusSeconds(120), null),
        Case("pasados los 120 s corresponde", listOf(disparada(t)), t.plusSeconds(300), null),
        Case("dentro de la tolerancia corresponde", listOf(disparada(t)), t.plusSeconds(118), null),
        Case("justo fuera de la tolerancia no", listOf(disparada(t)), t.plusSeconds(117), Duration.ofSeconds(3)),
        Case(
            "re-disparo de posposicion: cuenta el ultimo",
            listOf(disparada(t), disparada(t.plusSeconds(600))),
            t.plusSeconds(605),
            Duration.ofSeconds(115),
        ),
        Case(
            "orden del log no importa",
            listOf(disparada(t.plusSeconds(600)), disparada(t)),
            t.plusSeconds(605),
            Duration.ofSeconds(115),
        ),
        Case("sin disparo corresponde", emptyList(), t.plusSeconds(5), null),
        Case(
            "ignora otra alarma y otra ocurrencia",
            listOf(
                disparada(t),
                disparada(t.plusSeconds(60), alarmaId = "otra"),
                disparada(t.plusSeconds(60), para = Instant.parse("2026-11-27T08:00:00Z")),
            ),
            t.plusSeconds(120),
            null,
        ),
    )

    @Test
    fun `timeoutRemaining cuenta 2 min desde el ultimo alarma_disparada`() {
        cases.forEach { case ->
            assertEquals(case.expected, timeoutRemaining(case.events, "a1", ppRaw, case.now), case.name)
        }
    }
}
