package com.cauce.core.events

import com.cauce.core.events.projection.ProjectionRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class ConformanceTest {
    private val fixtures = Fixtures.loadAll()
    private val registry = ProjectionRegistry.aggregate(TestFeatures.modules)

    @Test
    fun `every fixture matches its projection`() {
        assumeTrue("No fixtures found; check -Dcauce.schemaDir", fixtures.isNotEmpty())
        assumeTrue(
            "No feature projections are registered yet. The conformance runner is wired; it will " +
                "enforce every fixture as soon as the first feature module exposes projections.",
            registry.keys().isNotEmpty(),
        )

        val failures = ConformanceRunner.run(registry, fixtures)
        assertTrue(
            failures.isEmpty(),
            "Conformance failures (${failures.size}/${fixtures.size}):\n" +
                failures.joinToString("\n"),
        )
    }
}
