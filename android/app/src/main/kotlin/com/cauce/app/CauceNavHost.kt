package com.cauce.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cauce.core.ui.EmptyState
import com.cauce.core.ui.Screen

/**
 * One `NavHost`; every feature contributes its own graph through [FeatureModule.navGraph]. The
 * home route stays here because it belongs to no feature.
 */
@Composable
fun CauceNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            Screen(title = "Cauce") {
                EmptyState(
                    title = "Sin alarmas todavía",
                    message = "El feature de alarmas todavía no está implementado. " +
                        "Cuando lo esté, sus alarmas activas aparecerán acá.",
                )
            }
        }

        FeatureRegistry.features.forEach { feature ->
            with(feature) { navGraph(navController) }
        }
    }
}

private const val HOME = "home"
