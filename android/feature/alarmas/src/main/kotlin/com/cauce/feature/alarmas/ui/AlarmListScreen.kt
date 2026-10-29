package com.cauce.feature.alarmas.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.BigButton
import com.cauce.core.ui.Card
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.ConfirmSheet
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.ListRow
import com.cauce.core.ui.Screen
import com.cauce.feature.alarmas.AlarmaActiva
import com.cauce.feature.alarmas.AlarmasActivas
import com.cauce.feature.alarmas.alarmaDesactivada
import com.cauce.feature.alarmas.device.AlarmasRuntime
import com.cauce.feature.alarmas.describe
import com.cauce.feature.alarmas.projectionParams
import com.cauce.feature.alarmas.repeticionFormFromJson
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.ZoneId

/** P1 on screen: active alarms, each with its on/off toggle (off = `alarma_desactivada`). */
@Composable
fun AlarmListScreen(
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onHistorial: () -> Unit,
) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val scope = rememberCoroutineScope()
    var pendingDeactivate by remember { mutableStateOf<AlarmaActiva?>(null) }

    RequestPostNotifications()
    RequestAlarmPermissions()

    val events by services.eventStore
        .observeByTypes(AlarmasRuntime.stateEventTypes)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val activas = remember(events) {
        val params = projectionParams(Instant.now(), ZoneId.systemDefault())
        val json = services.projections.compute(AlarmasActivas.key, events, params)
        CauceJson.output.decodeFromJsonElement(AlarmasActivas.serializer, json)
    }

    Screen(title = "Alarmas") {
        if (activas.isEmpty()) {
            EmptyState(
                title = "Sin alarmas activas",
                message = "Creá una alarma para que suene acá, aun con el teléfono bloqueado.",
            )
        }
        activas.forEach { alarma ->
            ListRow(
                title = alarma.titulo,
                subtitle = listOf(
                    "${alarma.hora} · ${repeticionFormFromJson(alarma.repeticion).describe()}",
                    "Próximo: ${formatInstantForUi(alarma.proximoDisparo)}",
                ).joinToString("\n"),
                onClick = { onEdit(alarma.alarmaId) },
                trailing = {
                    Switch(
                        checked = true,
                        onCheckedChange = { checked -> if (!checked) pendingDeactivate = alarma },
                    )
                },
            )
        }
        BigButton(text = "Nueva alarma", onClick = onNew)
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onHistorial) { Text("Historial de dosis") }
        }
    }

    ConfirmSheet(
        visible = pendingDeactivate != null,
        title = "Desactivar alarma",
        message = "«${pendingDeactivate?.titulo.orEmpty()}» deja de sonar. " +
            "No se borra: su historial de dosis sigue visible.",
        confirmLabel = "Desactivar",
        onConfirm = {
            val alarma = pendingDeactivate ?: return@ConfirmSheet
            pendingDeactivate = null
            scope.launch {
                services.eventStore.append(
                    listOf(
                        alarmaDesactivada(
                            AlarmasRuntime.deviceId(), Instant.now(), alarma.alarmaId, "usuario",
                        ),
                    ),
                )
                AlarmasRuntime.rescheduleAsync(context)
            }
        },
        onDismiss = { pendingDeactivate = null },
    )
}

/** POST_NOTIFICATIONS is a runtime permission since Android 13 (SPEC §5); ask on the list. */
@Composable
private fun RequestPostNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * SPEC §5: exact alarms (Android 12+) and full-screen intents (Android 14+) are granted in
 * Settings, not with a runtime dialog. Prompt from the list, re-check on resume, and let the
 * manifest's `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver reschedule.
 */
@Composable
private fun RequestAlarmPermissions() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canExact by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var canFullScreen by remember { mutableStateOf(canUseFullScreenIntent(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canExact = canScheduleExactAlarms(context)
                canFullScreen = canUseFullScreenIntent(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!canExact) {
        PermissionCard(
            title = "Alarmas exactas",
            message = "Sin este permiso Android puede correr las dosis hasta 15 minutos.",
            action = "Permitir",
            onOpen = {
                openSettings(
                    context,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)),
                )
            },
        )
    }
    if (!canFullScreen) {
        PermissionCard(
            title = "Pantalla completa",
            message = "Sin este permiso la alarma no se muestra sobre la pantalla bloqueada. " +
                "En ColorOS activa también, en Ajustes > Apps > Cauce: ventanas emergentes, " +
                "mostrar en pantalla de bloqueo e inicio automático.",
            action = "Permitir",
            onOpen = {
                openSettings(
                    context,
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)),
                )
            },
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    message: String,
    action: String,
    onOpen: () -> Unit,
) {
    Card {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = CauceSpacing.xs),
        )
        TextButton(onClick = onOpen) { Text(action) }
    }
}

private fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun canUseFullScreenIntent(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds lack the settings screen; the in-app prompt stays visible.
    }
}
