package com.cauce.feature.calendariogoogle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.BigButton
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.Chip
import com.cauce.core.ui.NumberInput
import com.cauce.core.ui.Screen
import com.cauce.core.ui.SectionHeader
import com.cauce.feature.calendariogoogle.ReunionesProximas
import com.cauce.feature.calendariogoogle.avisoReunionEditadoEvent
import com.cauce.feature.calendariogoogle.device.CalendarSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.ZoneId

private enum class Modo { MINUTOS, HORA, APAGADO }

private val HORA_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

/** Edits the aviso of a whole series (SPEC 2.3): appends ONE `aviso_reunion_editado`. */
@Composable
fun AvisoEditorScreen(serieId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val scope = rememberCoroutineScope()

    val initial = remember(serieId) {
        kotlinx.coroutines.runBlocking {
            val events = services.eventStore.observeByTypes(EVENT_TYPES).first()
            val json = services.projections.compute(
                ReunionesProximas.key, events, projectionParams(Instant.now(), ZoneId.systemDefault()),
            )
            CauceJson.output.decodeFromJsonElement(ReunionesProximas.serializer, json)
                .firstOrNull { it.serieId == serieId }
        }
    }
    val todoElDia = initial?.todoElDia ?: false

    var modo by remember {
        mutableStateOf(
            when {
                initial?.aviso?.apagado == true -> Modo.APAGADO
                todoElDia -> Modo.HORA
                else -> Modo.MINUTOS
            },
        )
    }
    var minutos by remember { mutableStateOf<Int?>(initial?.aviso?.minutosAntes ?: 10) }
    var hora by remember { mutableStateOf(initial?.aviso?.hora ?: "08:00") }

    val valido = when (modo) {
        Modo.MINUTOS -> minutos != null
        Modo.HORA -> HORA_RE.matches(hora)
        Modo.APAGADO -> true
    }

    Screen(title = initial?.titulo ?: "Aviso de la reunión") {
        Text(
            "El cambio alcanza a toda la serie: esta reunión y las siguientes que se repiten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionHeader(title = "Aviso")
        Row(horizontalArrangement = Arrangement.spacedBy(CauceSpacing.sm)) {
            if (todoElDia) {
                Chip(label = "A una hora", selected = modo == Modo.HORA, onClick = { modo = Modo.HORA })
            } else {
                Chip(label = "Minutos antes", selected = modo == Modo.MINUTOS, onClick = { modo = Modo.MINUTOS })
            }
            Chip(label = "Apagado", selected = modo == Modo.APAGADO, onClick = { modo = Modo.APAGADO })
        }
        when (modo) {
            Modo.MINUTOS -> NumberInput(
                value = minutos,
                onValueChange = { minutos = it },
                label = "Minutos antes",
                supportingText = "0 = suena al inicio",
            )
            Modo.HORA -> OutlinedTextField(
                value = hora,
                onValueChange = { hora = it.take(5) },
                label = { Text("Hora (HH:MM)") },
                isError = !HORA_RE.matches(hora),
                singleLine = true,
            )
            Modo.APAGADO -> Text("La reunión se sigue viendo, pero no suena.")
        }
        BigButton(
            text = "Guardar",
            enabled = valido,
            onClick = {
                scope.launch {
                    val event = avisoReunionEditadoEvent(
                        deviceId = CalendarSync.deviceId(),
                        ts = Instant.now(),
                        serieId = serieId,
                        minutosAntes = if (modo == Modo.MINUTOS) minutos else null,
                        hora = if (modo == Modo.HORA) hora else null,
                        apagado = modo == Modo.APAGADO,
                    )
                    services.eventStore.append(listOf(event))
                    onDone()
                }
            },
        )
    }
}
