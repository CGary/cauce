package com.cauce.feature.calendariogoogle.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cauce.core.events.CauceJson
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.BigButton
import com.cauce.core.ui.Card
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.ListRow
import com.cauce.core.ui.Screen
import com.cauce.core.ui.SectionHeader
import com.cauce.feature.calendariogoogle.ReunionProxima
import com.cauce.feature.calendariogoogle.ReunionesProximas
import com.cauce.feature.calendariogoogle.describeAviso
import com.cauce.feature.calendariogoogle.device.CalendarReader
import com.cauce.feature.calendariogoogle.device.CalendarSync
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val EVENT_TYPES: Set<String> = ReunionesProximas.eventTypes

internal fun projectionParams(now: Instant, zone: ZoneId): JsonObject = JsonObject(
    mapOf(
        "now" to JsonPrimitive(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(now)),
        "tz" to JsonPrimitive(zone.id),
    ),
)

/** Local day of a row: all-day meetings keep the UTC date of `inicio` (SPEC section 4). */
internal fun ReunionProxima.localDay(zone: ZoneId): LocalDate {
    val inicio = Instant.parse(inicio)
    return if (todoElDia) inicio.atZone(ZoneOffset.UTC).toLocalDate() else inicio.atZone(zone).toLocalDate()
}

/** P1 on screen: upcoming meetings grouped by local day; tap edits the aviso of the series. */
@Composable
fun ReunionesListScreen(onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val services = remember { context.cauceServices() }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(CalendarReader.hasPermission(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) CalendarSync.runAsync(context)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.READ_CALENDAR)
    }

    val events by services.eventStore
        .observeByTypes(EVENT_TYPES)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val zone = ZoneId.systemDefault()
    val reuniones = remember(events) {
        val json = services.projections.compute(
            ReunionesProximas.key, events, projectionParams(Instant.now(), zone),
        )
        CauceJson.output.decodeFromJsonElement(ReunionesProximas.serializer, json)
    }

    Screen(title = "Reuniones") {
        if (!granted) {
            Card {
                Text("Calendario", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Cauce lee (solo lectura) las reuniones de las cuentas de tu teléfono. " +
                        "Sin este permiso no se sincroniza nada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CauceSpacing.xs),
                )
                TextButton(onClick = { launcher.launch(Manifest.permission.READ_CALENDAR) }) { Text("Permitir") }
            }
        }
        if (reuniones.isEmpty()) {
            EmptyState(
                title = "Sin reuniones próximas",
                message = "Aparecen acá las reuniones de los próximos 14 días de tus calendarios visibles.",
            )
        }
        val dayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es"))
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        reuniones.groupBy { it.localDay(zone) }.toSortedMap().forEach { (day, rows) ->
            SectionHeader(title = day.format(dayFormat).replaceFirstChar { it.uppercase() })
            rows.forEach { r ->
                val hora = if (r.todoElDia) "Todo el día" else Instant.parse(r.inicio).atZone(zone).format(timeFormat)
                ListRow(
                    title = r.titulo,
                    subtitle = "$hora · ${r.cuenta}\n${describeAviso(r.aviso)}",
                    onClick = { onEdit(r.serieId) },
                )
            }
        }
        BigButton(
            text = "Sincronizar calendario",
            enabled = granted,
            onClick = {
                scope.launch {
                    val n = CalendarSync.run(context)
                    status = if (n == 0) "Sin cambios." else "Sincronizado: $n cambios."
                }
            },
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
