package com.cauce.core.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * One push/pull attempt as the phone saw it (D22). Local bookkeeping, not an event: it never
 * leaves the device. [at] is RFC 3339 UTC; [reason] is a short Spanish text, null when [ok].
 */
@Serializable
data class SyncAttempt(
    val at: String,
    val ok: Boolean,
    val reason: String? = null,
    val pushed: Int = 0,
    val pulled: Int = 0,
)

/** Pure helpers for the attempt log and the texts Home shows. JVM-tested. */
object SyncAttemptLog {
    const val MAX = 20

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SyncAttempt.serializer())

    /** Newest first, at most [max] entries. */
    fun append(log: List<SyncAttempt>, attempt: SyncAttempt, max: Int = MAX): List<SyncAttempt> =
        (listOf(attempt) + log).take(max)

    fun encode(log: List<SyncAttempt>): String = json.encodeToString(serializer, log)

    /** A corrupt value is treated as an empty log: it is only a diagnostic aid. */
    fun decode(raw: String?): List<SyncAttempt> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    fun succeeded(now: Instant, progress: SyncProgress) =
        SyncAttempt(at = now.toString(), ok = true, pushed = progress.pushed, pulled = progress.pulled)

    fun failed(now: Instant, error: Throwable, progress: SyncProgress) = SyncAttempt(
        at = now.toString(),
        ok = false,
        reason = reasonFor(error),
        pushed = progress.pushed,
        pulled = progress.pulled,
    )

    /** "sin red", "servidor 401", "servidor 5xx", "servidor 404", or "error interno". */
    fun reasonFor(error: Throwable): String = when (error) {
        is SyncHttpException -> if (error.code in 500..599) "servidor 5xx" else "servidor ${error.code}"
        is java.io.IOException -> "sin red"
        else -> "error interno (${error::class.simpleName ?: "desconocido"})"
    }

    /** "hace un momento", "hace 5 min", "hace 2 h", "hace 3 d". */
    fun ago(now: Instant, then: Instant): String {
        val minutes = Duration.between(then, now).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 1 -> "hace un momento"
            minutes < 60 -> "hace $minutes min"
            minutes < 60 * 24 -> "hace ${minutes / 60} h"
            else -> "hace ${minutes / (60 * 24)} d"
        }
    }

    /** The Home status line, from the newest attempt. */
    fun statusLine(log: List<SyncAttempt>, now: Instant): String {
        val last = log.firstOrNull() ?: return "Nunca sincronizado"
        val ago = ago(now, Instant.parse(last.at))
        return if (last.ok) "✓ Sincronizado $ago" else "⚠ Falló $ago: ${last.reason ?: "error"}"
    }

    /** One row of the attempts list: "✓ hace 5 min · subió 2, bajó 0". */
    fun rowLine(attempt: SyncAttempt, now: Instant): String {
        val head = if (attempt.ok) "✓" else "⚠"
        val tail = if (attempt.ok) "" else " · ${attempt.reason ?: "error"}"
        return "$head ${ago(now, Instant.parse(attempt.at))} · subió ${attempt.pushed}, bajó ${attempt.pulled}$tail"
    }
}

/** Persists the last [SyncAttemptLog.MAX] attempts in the `sync` DataStore, next to the cursor. */
class SyncAttempts(private val context: Context) {
    private val key = stringPreferencesKey("attempts")

    val flow: Flow<List<SyncAttempt>> = context.syncDataStore.data.map { SyncAttemptLog.decode(it[key]) }

    suspend fun record(attempt: SyncAttempt) {
        context.syncDataStore.edit { prefs ->
            prefs[key] = SyncAttemptLog.encode(SyncAttemptLog.append(SyncAttemptLog.decode(prefs[key]), attempt))
        }
    }
}
