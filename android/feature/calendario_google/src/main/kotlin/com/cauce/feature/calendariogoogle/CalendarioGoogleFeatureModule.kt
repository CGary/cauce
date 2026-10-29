package com.cauce.feature.calendariogoogle

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.cauce.core.events.FeatureModule
import com.cauce.core.events.projection.Projection

class CalendarioGoogleFeatureModule : FeatureModule {
    override val id: String = "calendario_google"

    override val eventTypes: Set<String> = setOf(
        "reunion_sincronizada",
        "reunion_eliminada_en_origen",
        "aviso_reunion_editado",
    )

    override val projections: List<Projection<*>> = listOf(ReunionesProximas)

    // Placeholders: the UI task adds the list and the aviso editor.
    override fun NavGraphBuilder.navGraph(nav: NavController) {}

    override fun onAppStart(ctx: Context) {}

    // No device effects of its own: `alarmas` reschedules after a pull (SPEC section 5).
    override fun onEventsPulled(ctx: Context, types: Set<String>) {}
}
