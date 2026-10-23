package com.cauce.core.events

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.cauce.core.events.projection.Projection

/**
 * A feature's entire contract with the rest of the app. Each feature exposes exactly one
 * implementation; `:app` lists them explicitly in its registry and wires them manually.
 */
interface FeatureModule {
    val id: String

    /** Event types this feature owns or consumes; a pull touching any of them re-applies effects. */
    val eventTypes: Set<String>

    val projections: List<Projection<*>>

    fun NavGraphBuilder.navGraph(nav: NavController)

    /** Called once on app start, for device-side effects (alarms, widgets, services). */
    fun onAppStart(ctx: Context) {}

    /** Called after a pull that brought any of [eventTypes], to re-apply device-side effects. */
    fun onEventsPulled(types: Set<String>) {}
}
