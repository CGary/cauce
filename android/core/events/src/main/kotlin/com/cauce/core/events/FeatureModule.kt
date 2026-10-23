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

    val projections: List<Projection<*>>

    fun NavGraphBuilder.navGraph(nav: NavController)

    /** Called on app start and after every pull that brought events, for device-side effects. */
    fun onAppStart(ctx: Context) {}
}
