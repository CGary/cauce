package com.cauce.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cauce.core.events.cauceServices
import com.cauce.core.sync.SyncAttempt
import com.cauce.core.sync.SyncAttemptLog
import com.cauce.core.sync.SyncAttempts
import com.cauce.core.sync.SyncFeedback
import com.cauce.core.sync.SyncScheduler
import com.cauce.core.ui.BigButton
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.ListRow
import com.cauce.core.ui.Screen
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val rowTime = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

private fun localTime(at: String): String =
    runCatching { Instant.parse(at).atZone(ZoneId.systemDefault()).format(rowTime) }.getOrDefault(at)

/** The attempt log, refreshed whenever the worker records one. */
@Composable
private fun rememberAttempts(): List<SyncAttempt> {
    val context = LocalContext.current
    val store = remember { SyncAttempts(context.applicationContext) }
    val attempts by store.flow.collectAsStateWithLifecycle(initialValue = emptyList())
    return attempts
}

/** "now", ticking every 30 s so "hace X" does not go stale while Home is open. */
@Composable
private fun rememberNow(): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    return now
}

/** Outbox size, re-read whenever the event table changes (append or markSynced). */
@Composable
private fun rememberPendingCount(): Int {
    val context = LocalContext.current
    val store = remember { context.cauceServices().eventStore }
    val events by store.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by produceState(0, events) { value = store.pending().size }
    return pending
}

/**
 * Home's sync line (D22, replaces D18's «Último evento»): status of the newest attempt and the
 * outbox size. Tapping it opens the list of the last 20 attempts.
 */
@Composable
fun SyncStatusRow(onClick: () -> Unit) {
    val attempts = rememberAttempts()
    val now = rememberNow()
    val pending = rememberPendingCount()
    val problem by SyncFeedback.problem.collectAsStateWithLifecycle()
    val failed = attempts.firstOrNull()?.ok == false

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = "${SyncAttemptLog.statusLine(attempts, now)} · Por subir: $pending",
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        problem?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The last 20 attempts, newest first, plus a manual sync trigger. */
@Composable
fun SyncAttemptsScreen() {
    val context = LocalContext.current
    val attempts = rememberAttempts()
    val now = rememberNow()
    val pending = rememberPendingCount()

    Screen(title = "Sincronización") {
        Text(
            "${SyncAttemptLog.statusLine(attempts, now)} · Por subir: $pending",
            style = MaterialTheme.typography.bodyMedium,
        )
        BigButton(text = "Sincronizar ahora", onClick = { SyncScheduler.syncNow(context.applicationContext) })
        if (attempts.isEmpty()) {
            EmptyState(title = "Sin intentos todavía")
        } else {
            attempts.forEach { attempt ->
                ListRow(title = SyncAttemptLog.rowLine(attempt, now), subtitle = localTime(attempt.at))
            }
        }
    }
}
