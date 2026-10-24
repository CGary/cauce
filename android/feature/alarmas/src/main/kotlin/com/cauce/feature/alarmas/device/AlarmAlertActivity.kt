package com.cauce.feature.alarmas.device

import android.app.KeyguardManager
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.BigButton
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.CauceTheme
import com.cauce.feature.alarmas.Alarm
import com.cauce.feature.alarmas.foldAlarms
import com.cauce.feature.alarmas.hasAtendida
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The full-screen UI behind the notification's `fullScreenIntent` (SPEC 2.3): shows over the
 * lock screen with the two actions, Hecho and Posponer 10 min. The buttons run the exact same
 * logic as the notification actions.
 */
class AlarmAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val alarmaId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARMA_ID)
        val programadaPara = intent.getStringExtra(AlarmReceiver.EXTRA_PROGRAMADA_PARA)
        if (alarmaId == null || programadaPara == null) {
            finish()
            return
        }

        setContent {
            CauceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlarmAlertScreen(
                        alarmaId = alarmaId,
                        programadaPara = programadaPara,
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
    }
}

@Composable
private fun AlarmAlertScreen(alarmaId: String, programadaPara: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val alarm by produceState<Alarm?>(initialValue = null) {
        val events = context.cauceServices().eventStore
            .observeByTypes(AlarmasRuntime.stateEventTypes)
            .first()
        value = foldAlarms(events).firstOrNull { it.id == alarmaId }
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
        BigButton(
            text = "Hecho",
            onClick = {
                scope.launch {
                    AlarmActions.hecho(context, alarmaId, programadaPara)
                    onDone()
                }
            },
        )
        TextButton(
            onClick = {
                scope.launch {
                    AlarmActions.posponer(context, alarmaId, programadaPara)
                    onDone()
                }
            },
        ) {
            Text("Posponer 10 min", style = MaterialTheme.typography.titleMedium)
        }
    }
}
