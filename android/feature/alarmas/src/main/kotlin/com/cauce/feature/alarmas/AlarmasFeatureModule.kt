package com.cauce.feature.alarmas

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.cauce.core.events.FeatureModule
import com.cauce.core.events.projection.Projection
import com.cauce.feature.alarmas.device.AlarmasRuntime
import com.cauce.feature.alarmas.device.AlarmNotifications
import com.cauce.feature.alarmas.ui.AlarmEditorScreen
import com.cauce.feature.alarmas.ui.AlarmListScreen
import com.cauce.feature.alarmas.ui.HistorialScreen

class AlarmasFeatureModule : FeatureModule {
    override val id: String = "alarmas"

    override val eventTypes: Set<String> = setOf(
        "alarma_creada",
        "alarma_modificada",
        "alarma_desactivada",
        "alarma_disparada",
        "alarma_atendida",
        "dosis_dada",
    )

    override val projections: List<Projection<*>> = listOf(
        AlarmasActivas,
        AdherenciaTratamiento,
        HistorialDosis,
    )

    override fun NavGraphBuilder.navGraph(nav: NavController) {
        composable(AlarmasRoutes.LIST) {
            AlarmListScreen(
                onNew = { nav.navigate(AlarmasRoutes.NEW) },
                onEdit = { alarmaId -> nav.navigate(AlarmasRoutes.edit(alarmaId)) },
                onHistorial = { nav.navigate(AlarmasRoutes.HISTORIAL) },
            )
        }
        composable(AlarmasRoutes.NEW) {
            AlarmEditorScreen(alarmaId = null, onDone = { nav.popBackStack() })
        }
        composable(AlarmasRoutes.EDIT) { entry ->
            AlarmEditorScreen(
                alarmaId = entry.arguments?.getString("alarmaId"),
                onDone = { nav.popBackStack() },
            )
        }
        composable(AlarmasRoutes.HISTORIAL) {
            HistorialScreen(onBack = { nav.popBackStack() })
        }
    }

    /** Channel first, then reprogram every active alarm (SPEC 2.7). */
    override fun onAppStart(ctx: Context) {
        AlarmNotifications.ensureChannel(ctx)
        AlarmasRuntime.rescheduleAsync(ctx)
    }

    /**
     * A pull that brought alarm state (including web events with `device_id: "web"`) forces a
     * P1 recompute and reprogram: otherwise an alarm created in the web would never ring (2.7).
     */
    override fun onEventsPulled(ctx: Context, types: Set<String>) {
        if (types.any { it in AlarmasRuntime.stateEventTypes }) {
            AlarmasRuntime.rescheduleAsync(ctx)
        }
    }
}

object AlarmasRoutes {
    const val LIST = "alarmas"
    const val NEW = "alarmas/nueva"
    const val EDIT = "alarmas/editar/{alarmaId}"
    const val HISTORIAL = "alarmas/historial"

    fun edit(alarmaId: String): String = "alarmas/editar/$alarmaId"
}
