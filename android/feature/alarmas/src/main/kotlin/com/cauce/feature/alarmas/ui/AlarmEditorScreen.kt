package com.cauce.feature.alarmas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cauce.core.events.CauceJson
import com.cauce.core.events.UuidV7
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.BigButton
import com.cauce.core.ui.Chip
import com.cauce.core.ui.NumberInput
import com.cauce.core.ui.Screen
import com.cauce.core.ui.SectionHeader
import com.cauce.feature.alarmas.Alarm
import com.cauce.feature.alarmas.AlarmasActivas
import com.cauce.feature.alarmas.EditorPrefill
import com.cauce.feature.alarmas.RepeticionForm
import com.cauce.feature.alarmas.editorPrefill
import com.cauce.feature.alarmas.isGcalId
import com.cauce.feature.alarmas.projectionParams
import com.cauce.feature.alarmas.WEEKDAY_CODES
import com.cauce.feature.alarmas.alarmaCreada
import com.cauce.feature.alarmas.alarmaModificada
import com.cauce.feature.alarmas.device.AlarmasRuntime
import com.cauce.feature.alarmas.foldAlarms
import com.cauce.feature.alarmas.repeticionFormFromJson
import com.cauce.feature.alarmas.toJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Create and edit share one form, and both emit the full state: `alarma_creada` with a fresh
 * `alarma_id`, or `alarma_modificada` keeping it (AGENTS.md: edits are full-state events).
 * The "Mensual" frequency offers the two modes of SPEC 2.1: día del mes (with "último día") and
 * n-ésimo día de la semana (with "último").
 */
@Composable
fun AlarmEditorScreen(alarmaId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val scope = rememberCoroutineScope()

    var loadedAlarm by remember { mutableStateOf<Alarm?>(null) }
    val existing by produceState<EditorPrefill?>(initialValue = null, alarmaId) {
        if (alarmaId == null || isGcalId(alarmaId)) {
            value = null
        } else {
            val events = services.eventStore
                .observeByTypes(AlarmasRuntime.stateEventTypes)
                .first()
            val alarm = foldAlarms(events).firstOrNull { it.id == alarmaId && !it.deactivated }
            if (alarm != null) {
                val zone = ZoneId.systemDefault()
                val json = services.projections.compute(
                    AlarmasActivas.key, events, projectionParams(Instant.now(), zone),
                )
                val row = CauceJson.output.decodeFromJsonElement(AlarmasActivas.serializer, json)
                    .firstOrNull { it.alarmaId == alarmaId }
                loadedAlarm = alarm
                value = editorPrefill(alarm, row, zone)
            }
        }
    }

    var fecha by remember { mutableStateOf("") }
    var titulo by remember { mutableStateOf("") }
    var tipo by remember { mutableStateOf("remedio") }
    var hora by remember { mutableStateOf("08:00") }
    var destinatario by remember { mutableStateOf("") }
    var remedio by remember { mutableStateOf("") }
    var form by remember { mutableStateOf(RepeticionForm()) }
    var limite by remember { mutableStateOf(Limite.NINGUNO) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(existing) {
        val prefill = existing ?: return@LaunchedEffect
        val loaded = loadedAlarm ?: return@LaunchedEffect
        titulo = loaded.titulo
        tipo = loaded.tipo
        hora = prefill.hora
        fecha = prefill.fecha
        destinatario = loaded.destinatario.orEmpty()
        remedio = loaded.remedio.orEmpty()
        val parsed = prefill.form
        form = parsed
        limite = when {
            parsed.count != null -> Limite.COUNT
            parsed.until != null -> Limite.UNTIL
            else -> Limite.NINGUNO
        }
    }

    Screen(title = if (alarmaId == null) "Nueva alarma" else "Editar alarma") {
        OutlinedTextField(
            value = titulo,
            onValueChange = { titulo = it },
            label = { Text("Título") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Tipo")
        Row(horizontalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
            listOf("remedio" to "Remedio", "reunion" to "Reunión", "otro" to "Otro").forEach { (value, label) ->
                Chip(label = label, selected = tipo == value, onClick = { tipo = value })
            }
        }

        if (tipo == "remedio") {
            OutlinedTextField(
                value = destinatario,
                onValueChange = { destinatario = it },
                label = { Text("Destinatario") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = remedio,
                onValueChange = { remedio = it },
                label = { Text("Remedio") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = hora,
            onValueChange = { hora = it },
            label = { Text("Hora (HH:MM)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FechaInicio(fecha = fecha, onChange = { fecha = it })

        RepeticionSection(form = form, limite = limite, onLimite = { limite = it }, onChange = { form = it })

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        BigButton(
            text = "Guardar",
            onClick = {
                val finalForm = when (limite) {
                    Limite.NINGUNO -> form.copy(count = null, until = null)
                    Limite.COUNT -> form.copy(until = null)
                    Limite.UNTIL -> form.copy(count = null)
                }
                val result = runCatching {
                    val repeticion = finalForm.toJson()
                    val device = AlarmasRuntime.deviceId()
                    val now = Instant.now()
                    if (alarmaId == null) {
                        alarmaCreada(
                            device, now, UuidV7.newId(), titulo.trim(), tipo, hora.trim(),
                            repeticion, destinatario.trim().ifBlank { null },
                            remedio.trim().ifBlank { null },
                            fecha = fecha.ifBlank { null },
                        )
                    } else {
                        alarmaModificada(
                            device, now, alarmaId, titulo.trim(), tipo, hora.trim(),
                            repeticion, destinatario.trim().ifBlank { null },
                            remedio.trim().ifBlank { null },
                            fecha = fecha.ifBlank { null },
                        )
                    }
                }
                result.fold(
                    onSuccess = { event ->
                        error = null
                        scope.launch {
                            services.eventStore.append(listOf(event))
                            AlarmasRuntime.rescheduleAsync(context)
                            onDone()
                        }
                    },
                    onFailure = { error = it.message ?: "Datos inválidos" },
                )
            },
        )
    }
}

/** Optional start date (SPEC 2.1): empty means "desde ahora". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FechaInicio(fecha: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SectionHeader(title = "Fecha de inicio")
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }) { Text(fecha.ifBlank { "Desde ahora" }) }
        if (fecha.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("Quitar") }
    }
    if (open) {
        val initial = runCatching { LocalDate.parse(fecha) }.getOrNull()
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) { DatePicker(state = state) }
    }
}

private enum class Limite { NINGUNO, COUNT, UNTIL }

@Composable
private fun RepeticionSection(
    form: RepeticionForm,
    limite: Limite,
    onLimite: (Limite) -> Unit,
    onChange: (RepeticionForm) -> Unit,
) {
    SectionHeader(title = "Repetición")
    Row(horizontalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
        listOf(
            null to "Una vez",
            "DAILY" to "Diaria",
            "WEEKLY" to "Semanal",
            "MONTHLY" to "Mensual",
            "HOURLY" to "Por horas",
        ).forEach { (value, label) ->
            Chip(label = label, selected = form.freq == value, onClick = { onChange(form.copy(freq = value)) })
        }
    }
    val freq = form.freq ?: return

    val intervalLabel = when (freq) {
        "DAILY" -> "Cada N días"
        "WEEKLY" -> "Cada N semanas"
        "MONTHLY" -> "Cada N meses"
        else -> "Cada N horas"
    }
    NumberInput(
        value = form.interval,
        onValueChange = { onChange(form.copy(interval = it ?: 1)) },
        label = intervalLabel,
        range = 1..365,
    )

    when (freq) {
        "WEEKLY" -> {
            Text("Días de la semana", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
                WEEKDAY_CODES.zip(listOf("L", "M", "X", "J", "V", "S", "D")).forEach { (code, label) ->
                    Chip(
                        label = label,
                        selected = code in form.weeklyDays,
                        onClick = {
                            val days = if (code in form.weeklyDays) {
                                form.weeklyDays - code
                            } else {
                                form.weeklyDays + code
                            }
                            onChange(form.copy(weeklyDays = days))
                        },
                    )
                }
            }
        }
        "MONTHLY" -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = !form.monthlyModeIsOrdinal,
                    onClick = { onChange(form.copy(monthlyModeIsOrdinal = false)) },
                )
                Text("Día del mes")
            }
            if (!form.monthlyModeIsOrdinal) {
                Column {
                    NumberInput(
                        value = if (form.monthlyDay == -1) null else form.monthlyDay,
                        onValueChange = { onChange(form.copy(monthlyDay = it ?: 1)) },
                        label = "Día del mes (1..31)",
                        supportingText = "Un mes sin ese día se salta",
                        range = 1..31,
                    )
                    Chip(
                        label = "Último día del mes",
                        selected = form.monthlyDay == -1,
                        onClick = {
                            onChange(form.copy(monthlyDay = if (form.monthlyDay == -1) 1 else -1))
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = form.monthlyModeIsOrdinal,
                    onClick = { onChange(form.copy(monthlyModeIsOrdinal = true)) },
                )
                Text("Día de la semana del mes")
            }
            if (form.monthlyModeIsOrdinal) {
                Column(verticalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
                        listOf(1 to "1.º", 2 to "2.º", 3 to "3.º", 4 to "4.º", 5 to "5.º", -1 to "Último")
                            .forEach { (value, label) ->
                                Chip(
                                    label = label,
                                    selected = form.monthlyOrdinal == value,
                                    onClick = { onChange(form.copy(monthlyOrdinal = value)) },
                                )
                            }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
                        WEEKDAY_CODES.zip(listOf("L", "M", "X", "J", "V", "S", "D")).forEach { (code, label) ->
                            Chip(
                                label = label,
                                selected = form.monthlyWeekday == code,
                                onClick = { onChange(form.copy(monthlyWeekday = code)) },
                            )
                        }
                    }
                }
            }
        }
    }

    SectionHeader(title = "Fin")
    Row(horizontalArrangement = Arrangement.spacedBy(com.cauce.core.ui.CauceSpacing.sm)) {
        Chip(label = "Sin fin", selected = limite == Limite.NINGUNO, onClick = { onLimite(Limite.NINGUNO) })
        Chip(label = "N disparos", selected = limite == Limite.COUNT, onClick = { onLimite(Limite.COUNT) })
        Chip(label = "Hasta fecha", selected = limite == Limite.UNTIL, onClick = { onLimite(Limite.UNTIL) })
    }
    when (limite) {
        Limite.COUNT -> NumberInput(
            value = form.count,
            onValueChange = { onChange(form.copy(count = it)) },
            label = "Cantidad de disparos",
            supportingText = "Primero incluido",
            range = 1..10_000,
        )
        Limite.UNTIL -> OutlinedTextField(
            value = form.until.orEmpty(),
            onValueChange = { onChange(form.copy(until = it.ifBlank { null })) },
            label = { Text("Última fecha (YYYY-MM-DD, inclusive)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Limite.NINGUNO -> Unit
    }
}
