package com.cauce.feature.alarmas.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.Card
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.Screen
import com.cauce.feature.alarmas.AdherenciaTratamiento
import com.cauce.feature.alarmas.adherenciaPercent
import com.cauce.feature.alarmas.projectionParams
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.ZoneId

/**
 * P2: one card per treatment. The projection gives counts only: no label or thresholds exist
 * in P2 (SPEC alarmas §4), so none is invented; the percentage is dadas / esperadas.
 */
@Composable
fun AdherenciaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }

    val events by services.eventStore
        .observeByTypes(AdherenciaTratamiento.eventTypes)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val tratamientos = remember(events) {
        val json = services.projections.compute(
            AdherenciaTratamiento.key, events, projectionParams(Instant.now(), ZoneId.systemDefault()),
        )
        CauceJson.output.decodeFromJsonElement(AdherenciaTratamiento.serializer, json)
    }

    Screen(title = "Adherencia") {
        if (tratamientos.isEmpty()) {
            EmptyState(title = "Sin tratamientos", message = "Aparecen las alarmas de tipo remedio.")
        }
        tratamientos.forEach { t ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("${t.remedio} · ${t.destinatario}", style = MaterialTheme.typography.titleSmall)
                val pct = adherenciaPercent(t.dosisDadas, t.dosisEsperadas)?.let { "$it %" } ?: "—"
                Text(
                    text = "Esperadas ${t.dosisEsperadas} · Dadas ${t.dosisDadas} · " +
                        "Ignoradas ${t.ignoradas} · $pct",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = CauceSpacing.xs),
                )
                t.ultimaDosis?.let {
                    Text(
                        text = "Última dosis: ${formatInstantForUi(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Esperadas: disparos hasta ahora. Dadas: dosis registradas. " +
                "Ignoradas: disparos ignorados sin dosis. % = dadas / esperadas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) { Text("Volver") }
    }
}
