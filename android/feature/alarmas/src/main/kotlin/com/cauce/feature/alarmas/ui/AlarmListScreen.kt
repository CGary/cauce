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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.cauce.core.ui.Card
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.ConfirmSheet
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.ListRow
import com.cauce.core.ui.Screen
import com.cauce.core.ui.SectionHeader
import com.cauce.feature.alarmas.AlarmaActiva
import com.cauce.feature.alarmas.AlarmasActivas
import com.cauce.feature.alarmas.alarmaDesactivada
import com.cauce.feature.alarmas.device.AlarmasRuntime
import com.cauce.feature.alarmas.describe
import com.cauce.feature.alarmas.gcalHeadline
import com.cauce.feature.alarmas.gcalMetas
import com.cauce.feature.alarmas.isGcalId
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
    onGoogleAviso: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val scope = rememberCoroutineScope()
    var pendingDeactivate by remember { mutableStateOf<AlarmaActiva?>(null) }

    RequestPostNotifications()

    val events by services.eventStore
        .observeByTypes(AlarmasRuntime.stateEventTypes)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val zone = ZoneId.systemDefault()
    val activas = remember(events) {
        val params = projectionParams(Instant.now(), zone)
        val json = services.projections.compute(AlarmasActivas.key, events, params)
        CauceJson.output.decodeFromJsonElement(AlarmasActivas.serializer, json)
    }
    val metas = remember(events) { gcalMetas(events) }
    val (google, propias) = activas.partition { isGcalId(it.alarmaId) }
    var googleOpen by rememberSaveable { mutableStateOf(false) }

    Screen(
        title = "Alarmas",
        actions = {
            TextButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Nueva alarma")
            }
        },
    ) {
        SyncStatusText()
        RequestAlarmPermissions()
        ColorOsReminderCard()
        if (propias.isEmpty()) {
            EmptyState(
                title = "Sin alarmas activas",
                message = "Creá una alarma para que suene acá, aun con el teléfono bloqueado.",
            )
        }
        propias.forEach { alarma ->
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
        if (google.isNotEmpty()) {
            SectionHeader(
                title = "Reuniones de Google (${google.size})",
                modifier = Modifier.clickable { googleOpen = !googleOpen },
                trailing = {
                    Icon(
                        if (googleOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (googleOpen) "Contraer" else "Expandir",
                    )
                },
            )
        }
        if (googleOpen) {
            google.forEach { alarma ->
                // Read-only (SPEC 2.10): the aviso is edited in calendario_google's screen.
                val meta = metas[alarma.alarmaId.removePrefix("gcal:")]
                val aviso = Instant.parse(alarma.proximoDisparo)
                val headline = if (meta != null) {
                    gcalHeadline(meta.inicio, meta.todoElDia, aviso, zone)
                } else {
                    "Aviso ${formatInstantForUi(alarma.proximoDisparo)}"
                }
                ListRow(
                    title = alarma.titulo,
                    subtitle = listOf(
                        headline,
                        "${meta?.cuenta ?: "Google Calendar"} · ${formatInstantForUi(alarma.proximoDisparo)}",
                    ).joinToString("\n"),
                    onClick = meta?.let { m -> { onGoogleAviso(m.serieId) } },
                    trailing = {
                        AssistChip(
                            onClick = { meta?.let { onGoogleAviso(it.serieId) } },
                            label = { Text("Google") },
                            leadingIcon = {
                                Icon(Icons.Default.DateRange, contentDescription = null)
                            },
                        )
                    },
                )
            }
        }
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
            message = "Sin este permiso la alarma no se muestra sobre la pantalla bloqueada.",
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

/**
 * SPEC §5: ColorOS has no public API for autostart, popups, lock screen and full screen, so the
 * list reminds the owner. Shown until dismissed once (flag in SharedPreferences) and again
 * whenever the full-screen permission is missing.
 */
@Composable
private fun ColorOsReminderCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("alarmas_ui", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("coloros_reminder_dismissed", false)) }
    var hiddenNow by remember { mutableStateOf(false) }
    val show = !hiddenNow && (!dismissed || !canUseFullScreenIntent(context))
    if (!show) return
    Card {
        Text("Requisitos del teléfono (ColorOS)", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Para que las alarmas suenen siempre, en Ajustes > Apps > Cauce activa: " +
                "inicio automático, ventanas emergentes, mostrar en pantalla de bloqueo y " +
                "pantalla completa (Notificaciones > Alarmas); batería «Sin restricciones».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = CauceSpacing.xs),
        )
        TextButton(onClick = {
            hiddenNow = true
            dismissed = true
            prefs.edit().putBoolean("coloros_reminder_dismissed", true).apply()
        }) { Text("Entendido") }
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
