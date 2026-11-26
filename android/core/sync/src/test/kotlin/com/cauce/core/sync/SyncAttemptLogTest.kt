package com.cauce.core.sync

import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncAttemptLogTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")

    private fun attempt(minutesAgo: Long, ok: Boolean = true, reason: String? = null) =
        SyncAttempt(at = now.minusSeconds(minutesAgo * 60).toString(), ok = ok, reason = reason)

    @Test
    fun `append keeps the newest first and at most 20`() {
        var log = emptyList<SyncAttempt>()
        repeat(25) { i -> log = SyncAttemptLog.append(log, attempt(minutesAgo = 100L - i)) }

        assertEquals(20, log.size)
        assertEquals(attempt(76).at, log.first().at)
        assertEquals(attempt(95).at, log.last().at)
    }

    @Test
    fun `the log survives an encode and decode round trip`() {
        val log = listOf(
            SyncAttempt(at = "2026-11-26T11:55:00Z", ok = false, reason = "servidor 401", pushed = 0, pulled = 0),
            SyncAttempt(at = "2026-11-26T11:40:00.123Z", ok = true, pushed = 3, pulled = 7),
        )
        assertEquals(log, SyncAttemptLog.decode(SyncAttemptLog.encode(log)))
    }

    @Test
    fun `a missing or corrupt value decodes to an empty log`() {
        assertEquals(emptyList(), SyncAttemptLog.decode(null))
        assertEquals(emptyList(), SyncAttemptLog.decode(""))
        assertEquals(emptyList(), SyncAttemptLog.decode("{not json"))
    }

    @Test
    fun `failure reasons are short and readable`() {
        val cases = listOf(
            SyncHttpException(401, "") to "servidor 401",
            SyncHttpException(500, "") to "servidor 5xx",
            SyncHttpException(503, "") to "servidor 5xx",
            SyncHttpException(404, "") to "servidor 404",
            SyncHttpException(429, "") to "servidor 429",
            IOException("timeout") to "sin red",
            UnknownHostException("cauce.example") to "sin red",
            SerializationException("bad json") to "error interno (SerializationException)",
        )
        cases.forEach { (error, want) -> assertEquals(want, SyncAttemptLog.reasonFor(error), "$error") }
    }

    @Test
    fun `failed and succeeded carry the counters`() {
        val progress = SyncProgress().apply { pushed = 2; pulled = 5 }
        val ok = SyncAttemptLog.succeeded(now, progress)
        assertEquals(SyncAttempt(at = "2026-11-26T12:00:00Z", ok = true, pushed = 2, pulled = 5), ok)

        val ko = SyncAttemptLog.failed(now, SyncHttpException(502, ""), progress)
        assertEquals(false, ko.ok)
        assertEquals("servidor 5xx", ko.reason)
        assertEquals(2, ko.pushed)
    }

    @Test
    fun `relative time`() {
        val cases = listOf(
            0L to "hace un momento",
            1L to "hace 1 min",
            59L to "hace 59 min",
            60L to "hace 1 h",
            60L * 23 + 59 to "hace 23 h",
            60L * 24 to "hace 1 d",
            60L * 24 * 3 + 5 to "hace 3 d",
            -5L to "hace un momento", // clock moved back: never "hace -5 min"
        )
        cases.forEach { (minutes, want) ->
            assertEquals(want, SyncAttemptLog.ago(now, now.minusSeconds(minutes * 60)), "$minutes min")
        }
    }

    @Test
    fun `status line from the newest attempt`() {
        assertEquals("Nunca sincronizado", SyncAttemptLog.statusLine(emptyList(), now))
        assertEquals(
            "✓ Sincronizado hace 5 min",
            SyncAttemptLog.statusLine(listOf(attempt(5), attempt(20, ok = false, reason = "sin red")), now),
        )
        assertEquals(
            "⚠ Falló hace 2 h: servidor 401",
            SyncAttemptLog.statusLine(listOf(attempt(120, ok = false, reason = "servidor 401"), attempt(200)), now),
        )
    }

    @Test
    fun `row line shows counters and the reason`() {
        val ok = SyncAttempt(at = "2026-11-26T11:55:00Z", ok = true, pushed = 2, pulled = 0)
        assertEquals("✓ hace 5 min · subió 2, bajó 0", SyncAttemptLog.rowLine(ok, now))
        val ko = SyncAttempt(at = "2026-11-26T11:00:00Z", ok = false, reason = "sin red")
        assertEquals("⚠ hace 1 h · subió 0, bajó 0 · sin red", SyncAttemptLog.rowLine(ko, now))
    }
}
