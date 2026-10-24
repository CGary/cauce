package com.cauce.feature.alarmas.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.cauce.feature.alarmas.HistorialDosis
import com.cauce.feature.alarmas.HistorialDosisEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** P3: dose history, optionally filtered by destinatario. Deactivated alarms keep showing (2.8). */
@Composable
fun HistorialScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    var destinatario by remember { mutableStateOf("") }

    val events by services.eventStore
        .observeByTypes(setOf("dosis_dada"))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val historial = remember(events, destinatario) {
        val params = JsonObject(
            destinatario.trim().takeIf { it.isNotEmpty() }
                ?.let { mapOf("destinatario" to JsonPrimitive(it)) }
                .orEmpty(),
        )
        val json = services.projections.compute(HistorialDosis.key, events, params)
        CauceJson.output.decodeFromJsonElement(HistorialDosis.serializer, json)
    }

    Screen(title = "Historial de dosis") {
        OutlinedTextField(
            value = destinatario,
            onValueChange = { destinatario = it },
            label = { Text("Destinatario (vacío = todos)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (historial.isEmpty()) {
            EmptyState(title = "Sin dosis registradas")
        }
        historial.forEach { entry: HistorialDosisEntry ->
            ListRow(
                title = "${entry.remedio} · ${entry.destinatario}",
                subtitle = formatInstantForUi(entry.ts),
            )
        }
        androidx.compose.material3.TextButton(onClick = onBack) { Text("Volver") }
    }
}
