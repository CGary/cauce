package com.cauce.feature.alarmas

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.cauce.core.events.FeatureModule
import com.cauce.core.events.projection.Projection

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

    override fun NavGraphBuilder.navGraph(nav: NavController) = Unit
}
