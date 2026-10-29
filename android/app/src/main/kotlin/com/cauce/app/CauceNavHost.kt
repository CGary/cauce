package com.cauce.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cauce.core.ui.ListRow
import com.cauce.core.ui.Screen
import com.cauce.feature.alarmas.AlarmasRoutes
import com.cauce.feature.calendariogoogle.CalendarioGoogleRoutes

/**
 * One `NavHost`; every feature contributes its own graph through [FeatureModule.navGraph]. The
 * home route stays here because it belongs to no feature.
 */
@Composable
fun CauceNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HOME) {
        composable(HOME) {
            Screen(title = "Cauce") {
                ListRow(
                    title = "Alarmas",
                    onClick = { navController.navigate(AlarmasRoutes.LIST) },
                )
                ListRow(
                    title = "Reuniones",
                    onClick = { navController.navigate(CalendarioGoogleRoutes.LIST) },
                )
            }
        }

        FeatureRegistry.features.forEach { feature ->
            with(feature) { navGraph(navController) }
        }
    }
}

private const val HOME = "home"
