package com.cauce.feature.alarmas.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.feature.alarmas.AdherenciaTratamiento
import com.cauce.feature.alarmas.AlarmasActivas
import com.cauce.feature.alarmas.HistorialDosis
import com.cauce.feature.alarmas.adherenciaPercent
import com.cauce.feature.alarmas.device.AlarmasRuntime
import com.cauce.feature.alarmas.newestTs
import com.cauce.feature.alarmas.nextAlarmLabel
import com.cauce.feature.alarmas.projectionParams
import com.cauce.feature.alarmas.relativeAgo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.ZoneId

/** One line per Home card, computed from the existing projections (read-only). */
data class AlarmasHomeSummary(
    val proxima: String,
    val historial: String,
    val adherencia: String,
)

@Composable
fun rememberAlarmasHomeSummary(): AlarmasHomeSummary {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val state by services.eventStore
        .observeByTypes(AlarmasRuntime.stateEventTypes + AdherenciaTratamiento.eventTypes + HistorialDosis.eventTypes)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(state) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val params = projectionParams(now, zone)
        val activas = CauceJson.output.decodeFromJsonElement(
            AlarmasActivas.serializer,
            services.projections.compute(AlarmasActivas.key, state, params),
        )
        val historial = CauceJson.output.decodeFromJsonElement(
            HistorialDosis.serializer,
            services.projections.compute(HistorialDosis.key, state, JsonObject(emptyMap())),
        )
        val adherencia = CauceJson.output.decodeFromJsonElement(
            AdherenciaTratamiento.serializer,
            services.projections.compute(AdherenciaTratamiento.key, state, params),
        )
        val esperadas = adherencia.sumOf { it.dosisEsperadas }
        val pct = adherenciaPercent(adherencia.sumOf { it.dosisDadas }, esperadas)
        AlarmasHomeSummary(
            proxima = nextAlarmLabel(activas, now, zone) ?: "Sin alarmas activas",
            historial = historial.firstOrNull()
                ?.let { "Última: ${it.remedio} · ${it.destinatario}, ${formatInstantForUi(it.ts)}" }
                ?: "Sin dosis registradas",
            adherencia = when {
                adherencia.isEmpty() -> "Sin tratamientos"
                pct == null -> "${adherencia.size} tratamientos"
                else -> "${adherencia.size} tratamientos · $pct% de dosis dadas"
            },
        )
    }
}

/**
 * core exposes no last-sync timestamp (only the `server_seq` cursor), so the proxy is the
 * newest event `ts` in the local store, labeled as such (SPEC alarmas §8).
 */
@Composable
fun SyncStatusText(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val events by services.eventStore.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val label = remember(events) { relativeAgo(Instant.now(), newestTs(events)) }
    Text(
        text = "Último evento: $label",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
