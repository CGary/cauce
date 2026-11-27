package com.cauce.feature.alarmas.device

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.CauceTheme
import com.cauce.feature.alarmas.Alarm
import com.cauce.feature.alarmas.hasAtendida
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The full-screen UI behind the notification's `fullScreenIntent` (SPEC 2.3): shows over the
 * lock screen with the two actions, Hecho and Posponer 10 min. Both need a ~1 s hold
 * ([HoldButton]) so pocket touches cannot attend the alarm (SPEC §8); they run the exact same
 * logic as the notification actions.
 */
class AlarmAlertActivity : ComponentActivity() {

    /** The firing on screen; `singleTop` + [onNewIntent] swap it for the newest one. */
    private var firing by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        firing = firingOf(intent)
        if (firing == null) {
            finish()
            return
        }

        setContent {
            CauceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val current = firing
                    if (current != null) {
                        key(current) {
                            AlarmAlertScreen(
                                alarmaId = current.first,
                                programadaPara = current.second,
                                onDone = { finish() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newest = firingOf(intent) ?: return
        setIntent(intent)
        firing = newest
    }

    private fun firingOf(intent: Intent?): Pair<String, String>? {
        val alarmaId = intent?.getStringExtra(AlarmReceiver.EXTRA_ALARMA_ID) ?: return null
        val programadaPara = intent.getStringExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA) ?: return null
        return alarmaId to programadaPara
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }
}

@Composable
private fun AlarmAlertScreen(alarmaId: String, programadaPara: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val alarm by produceState<Alarm?>(initialValue = null) {
        value = AlarmasRuntime.loadAlarm(context, alarmaId, programadaPara)
    }

    // Close this screen when the firing is closed elsewhere: a notification action or the
    // 2-minute timeout appends a terminal `alarma_atendida`, and the log is the source of truth.
    LaunchedEffect(alarmaId, programadaPara) {
        context.cauceServices().eventStore
            .observeByTypes(setOf("alarma_atendida"))
            .collect { events ->
                if (hasAtendida(events, alarmaId, programadaPara)) onDone()
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CauceSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(CauceSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = alarm?.titulo ?: "Alarma",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (alarm?.tipo == "remedio") {
            Text(
                text = listOfNotNull(alarm?.remedio, alarm?.destinatario).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        var hint by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        fun act(action: suspend () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                action()
                onDone()
            }
        }
        HoldButton(
            text = "Hecho",
            onConfirmed = { act { AlarmActions.hecho(context, alarmaId, programadaPara) } },
            onShortPress = { hint = true },
        )
        HoldButton(
            text = "Posponer 10 min",
            onConfirmed = { act { AlarmActions.posponer(context, alarmaId, programadaPara) } },
            onShortPress = { hint = true },
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            fill = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
        )
        Text(
            text = "Mantén presionado 1 segundo",
            style = MaterialTheme.typography.bodyMedium,
            color = if (hint) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
