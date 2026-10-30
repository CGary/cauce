package com.cauce.app

import com.cauce.core.events.FeatureModule
import com.cauce.feature.alarmas.AlarmasFeatureModule
import com.cauce.feature.calendariogoogle.CalendarioGoogleFeatureModule
import com.cauce.feature.calendariogoogle.CalendarioGoogleRoutes

/** The explicit feature list: no service loader, no reflection, no DI framework. */
object FeatureRegistry {
    val features: List<FeatureModule> = listOf(
        AlarmasFeatureModule(
            openGoogleAviso = { nav, serieId -> nav.navigate(CalendarioGoogleRoutes.aviso(serieId)) },
        ),
        CalendarioGoogleFeatureModule(),
    )
}
