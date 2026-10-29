package com.cauce.feature.calendariogoogle

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.cauce.core.events.FeatureModule
import com.cauce.core.events.projection.Projection
import com.cauce.feature.calendariogoogle.device.CalendarSync
import com.cauce.feature.calendariogoogle.ui.AvisoEditorScreen
import com.cauce.feature.calendariogoogle.ui.ReunionesListScreen

class CalendarioGoogleFeatureModule : FeatureModule {
    override val id: String = "calendario_google"

    override val eventTypes: Set<String> = setOf(
        "reunion_sincronizada",
        "reunion_eliminada_en_origen",
        "aviso_reunion_editado",
    )

    override val projections: List<Projection<*>> = listOf(ReunionesProximas)

    override fun NavGraphBuilder.navGraph(nav: NavController) {
        composable(CalendarioGoogleRoutes.LIST) {
            ReunionesListScreen(onEdit = { serieId -> nav.navigate(CalendarioGoogleRoutes.aviso(serieId)) })
        }
        composable(CalendarioGoogleRoutes.AVISO) { entry ->
            AvisoEditorScreen(
                serieId = entry.arguments?.getString("serieId").orEmpty(),
                onDone = { nav.popBackStack() },
            )
        }
    }

    /** Observer on the calendar provider plus the 15-minute worker (SPEC 2.6); both no-op without READ_CALENDAR. */
    override fun onAppStart(ctx: Context) {
        CalendarSync.start(ctx)
        CalendarSync.runAsync(ctx)
    }

    // No device effects of its own: `alarmas` reschedules after a pull (SPEC section 5).
    override fun onEventsPulled(ctx: Context, types: Set<String>) {}
}

object CalendarioGoogleRoutes {
    const val LIST = "calendario_google"
    const val AVISO = "calendario_google/aviso/{serieId}"

    fun aviso(serieId: String): String = "calendario_google/aviso/$serieId"
}
