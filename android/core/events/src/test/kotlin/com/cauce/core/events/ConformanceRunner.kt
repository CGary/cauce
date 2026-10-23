package com.cauce.core.events

import com.cauce.core.events.projection.ProjectionRegistry

/**
 * The same walk the Go conformance runner does: every fixture is run through the registry and its
 * `expected` value is compared structurally. An unknown projection is a failure, never a skip.
 */
object ConformanceRunner {
    fun run(registry: ProjectionRegistry, fixtures: List<FixtureFile>): List<String> {
        val failures = mutableListOf<String>()

        for (file in fixtures) {
            val key = file.fixture.projection
            val expectedPrefix = "${file.featureId}."

            if (!key.startsWith(expectedPrefix)) {
                failures += "${file.path.path}: projection '$key' must start with '$expectedPrefix'"
                continue
            }

            if (registry[key] == null) {
                failures += "${file.path.path}: unknown projection '$key' " +
                    "(registered: ${registry.keys().sorted()})"
                continue
            }

            try {
                val events = Fixtures.eventsOf(file.fixture)
                val actual = registry.compute(key, events, file.fixture.params)
                JsonEquality.firstDifference(file.fixture.expected, actual)?.let { difference ->
                    failures += "${file.path.path}: $difference"
                }
            } catch (e: Exception) {
                failures += "${file.path.path}: ${e::class.simpleName}: ${e.message}"
            }
        }

        return failures
    }
}
