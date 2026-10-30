package com.cauce.feature.alarmas.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.ListRow
import com.cauce.core.ui.Screen
import com.cauce.core.ui.SectionHeader
import com.cauce.feature.alarmas.HistorialDosis
import com.cauce.feature.alarmas.groupHistorialByDay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es"))
private val HM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** P3: dose history grouped by day (newest first), optionally filtered by destinatario (2.8). */
@Composable
fun HistorialScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    var destinatario by remember { mutableStateOf("") }

    val events by services.eventStore
        .observeByTypes(setOf("dosis_dada"))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val zone = ZoneId.systemDefault()
    val grupos = remember(events, destinatario) {
        val params = JsonObject(
            destinatario.trim().takeIf { it.isNotEmpty() }
                ?.let { mapOf("destinatario" to JsonPrimitive(it)) }
                .orEmpty(),
        )
        val json = services.projections.compute(HistorialDosis.key, events, params)
        groupHistorialByDay(CauceJson.output.decodeFromJsonElement(HistorialDosis.serializer, json), zone)
    }

    Screen(title = "Historial de dosis") {
        OutlinedTextField(
            value = destinatario,
            onValueChange = { destinatario = it },
            label = { Text("Destinatario (vacío = todos)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (grupos.isEmpty()) {
            EmptyState(title = "Sin dosis registradas")
        }
        grupos.forEach { (day, entries) ->
            SectionHeader(title = day.format(DAY_FORMAT).replaceFirstChar { it.uppercase() })
            entries.forEach { entry ->
                // P3 lists only given doses, so the estado is always "Dada".
                ListRow(
                    title = "${entry.remedio} · ${entry.destinatario}",
                    subtitle = "Dada ${Instant.parse(entry.ts).atZone(zone).format(HM_FORMAT)}",
                )
            }
        }
        TextButton(onClick = onBack) { Text("Volver") }
    }
}
