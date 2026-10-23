package com.cauce.feature.alarmas

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.cauce.core.events.FeatureModule
import com.cauce.core.events.projection.Projection

/**
 * Skeleton only. Projections, screens and the AlarmManager integration land in the `/feature
 * alarmas` run; until then this module registers nothing and the app stays empty.
 */
class AlarmasFeatureModule : FeatureModule {
    override val id: String = "alarmas"

    override val projections: List<Projection<*>> = emptyList()

    override fun NavGraphBuilder.navGraph(nav: NavController) = Unit
}
