package com.cauce.feature.alarmas.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
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
import com.cauce.core.ui.SectionHeader
import com.cauce.feature.alarmas.HistorialDosis
import com.cauce.feature.alarmas.distinctDestinatarios
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
@OptIn(ExperimentalMaterial3Api::class)
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

    val todos = remember(events) {
        val json = services.projections.compute(HistorialDosis.key, events, JsonObject(emptyMap()))
        distinctDestinatarios(CauceJson.output.decodeFromJsonElement(HistorialDosis.serializer, json))
    }
    var menuOpen by remember { mutableStateOf(false) }

    BackScreen(title = "Historial de dosis", onBack = onBack) {
        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = destinatario.ifEmpty { "Todos" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Destinatario") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                (listOf("") + todos).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.ifEmpty { "Todos" }) },
                        onClick = {
                            destinatario = option
                            menuOpen = false
                        },
                    )
                }
            }
        }
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
    }
}
