package com.cauce.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cauce.core.events.cauceServices
import com.cauce.core.ui.Card
import com.cauce.core.ui.CauceSpacing
import com.cauce.core.ui.Screen
import com.cauce.feature.alarmas.AlarmasRoutes
import com.cauce.feature.alarmas.ui.SyncStatusText
import com.cauce.feature.alarmas.ui.rememberAlarmasHomeSummary
import com.cauce.feature.calendariogoogle.CalendarioGoogleRoutes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * One `NavHost`; every feature contributes its own graph through [FeatureModule.navGraph]. The
 * home route stays here because it belongs to no feature. Home stays the hub (cards), which
 * needs no change to the navigation graph.
 */
@Composable
fun CauceNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            val alarmas = rememberAlarmasHomeSummary()
            Screen(title = "Cauce") {
                SyncStatusText()
                HomeCard(
                    title = "Alarmas",
                    subtitle = alarmas.proxima,
                    icon = Icons.Default.Notifications,
                    onClick = { navController.navigate(AlarmasRoutes.LIST) },
                )
                HomeCard(
                    title = "Reuniones",
                    subtitle = reunionesSubtitle(),
                    icon = Icons.Default.DateRange,
                    onClick = { navController.navigate(CalendarioGoogleRoutes.LIST) },
                )
                HomeCard(
                    title = "Historial de dosis",
                    subtitle = alarmas.historial,
                    icon = Icons.Default.List,
                    onClick = { navController.navigate(AlarmasRoutes.HISTORIAL) },
                )
                HomeCard(
                    title = "Adherencia",
                    subtitle = alarmas.adherencia,
                    icon = Icons.Default.CheckCircle,
                    onClick = { navController.navigate(AlarmasRoutes.ADHERENCIA) },
                )
            }
        }

        FeatureRegistry.features.forEach { feature ->
            with(feature) { navGraph(navController) }
        }
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(CauceSpacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = CauceSpacing.xs),
                )
            }
        }
    }
}

/** "N en 14 días" from the calendario_google P1, read through the registry (no feature import). */
@Composable
private fun reunionesSubtitle(): String {
    val context = LocalContext.current
    val svc = remember { context.cauceServices() }
    val events by svc.eventStore
        .observeByTypes(setOf("reunion_sincronizada", "reunion_eliminada_en_origen", "aviso_reunion_editado"))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(events) {
        val params = JsonObject(
            mapOf(
                "now" to JsonPrimitive(Instant.now().toString().substringBefore('.').removeSuffix("Z") + "Z"),
                "tz" to JsonPrimitive(ZoneId.systemDefault().id),
            ),
        )
        val n = (svc.projections.compute("calendario_google.reuniones_proximas", events, params) as? JsonArray)?.size ?: 0
        if (n == 0) "Sin reuniones próximas" else "$n en 14 días"
    }
}

private const val HOME = "home"
