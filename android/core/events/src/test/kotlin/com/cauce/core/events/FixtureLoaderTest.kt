package com.cauce.core.events

import com.cauce.feature.alarmas.AlarmasFeatureModule
import com.cauce.feature.calendariogoogle.CalendarioGoogleFeatureModule
import kotlin.test.Test
import kotlin.test.assertTrue

/** The test-only feature list. Adding a feature here puts its fixtures under conformance. */
internal object TestFeatures {
    val modules: List<FeatureModule> = listOf(AlarmasFeatureModule(), CalendarioGoogleFeatureModule())
}

class FixtureLoaderTest {
    @Test
    fun `fixtures parse, belong to their feature and decode to envelopes`() {
        val fixtures = Fixtures.loadAll()
        assertTrue(fixtures.isNotEmpty(), "No fixtures found; check -Dcauce.schemaDir")

        val failures = fixtures.mapNotNull { file ->
            val fixture = file.fixture
            when {
                !fixture.projection.startsWith("${file.featureId}.") ->
                    "${file.path.path}: projection '${fixture.projection}' must start with '${file.featureId}.'"

                fixture.events.isEmpty() ->
                    "${file.path.path}: fixture has no events"

                else -> runCatching { Fixtures.eventsOf(fixture) }.exceptionOrNull()
                    ?.let { "${file.path.path}: ${it::class.simpleName}: ${it.message}" }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Fixture files that do not match schema/README.md:\n" + failures.joinToString("\n"),
        )
    }
}
