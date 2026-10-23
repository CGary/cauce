package com.cauce.app

import com.cauce.core.events.FeatureModule
import com.cauce.feature.alarmas.AlarmasFeatureModule

/** The explicit feature list: no service loader, no reflection, no DI framework. */
object FeatureRegistry {
    val features: List<FeatureModule> = listOf(
        AlarmasFeatureModule(),
    )
}
