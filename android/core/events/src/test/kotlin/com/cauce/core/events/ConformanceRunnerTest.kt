package com.cauce.core.events

import com.cauce.core.events.projection.Projection
import com.cauce.core.events.projection.ProjectionRegistry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConformanceRunnerTest {
    @Serializable
    private data class Output(val count: Int)

    private val projection = object : Projection<Output> {
        override val key = "fake.count"
        override val eventTypes = setOf("fake_event")
        override val serializer: KSerializer<Output> = Output.serializer()
        override fun compute(events: List<Envelope>, params: JsonObject): Output = Output(events.size)
    }

    private val registry = ProjectionRegistry(listOf(projection))

    private fun fixture(
        key: String,
        expected: kotlinx.serialization.json.JsonElement,
    ) = FixtureFile(
        path = File("synthetic/$key.json"),
        featureId = "fake",
        fixture = Fixture(
            projection = key,
            events = listOf(
                buildJsonObject {
                    put("id", "e1")
                    put("type", "fake_event")
                    put("v", 1)
                    put("ts", "2026-10-22T10:00:00Z")
                    put("payload", buildJsonObject { })
                },
            ),
            expected = expected,
        ),
    )

    @Test
    fun `a matching fixture passes`() {
        val fixture = fixture("fake.count", buildJsonObject { put("count", 1) })
        assertEquals(emptyList(), ConformanceRunner.run(registry, listOf(fixture)))
    }

    @Test
    fun `an unknown projection fails with a clear message`() {
        val fixture = fixture("fake.missing", buildJsonObject { put("count", 1) })
        val failures = ConformanceRunner.run(registry, listOf(fixture))

        assertEquals(1, failures.size)
        assertTrue("unknown projection 'fake.missing'" in failures.single())
    }

    @Test
    fun `a projection from another feature is rejected`() {
        val fixture = FixtureFile(
            path = File("synthetic/foreign.json"),
            featureId = "alarmas",
            fixture = Fixture(
                projection = "fake.count",
                events = emptyList(),
                expected = buildJsonObject { put("count", 0) },
            ),
        )
        val failures = ConformanceRunner.run(registry, listOf(fixture))

        assertEquals(1, failures.size)
        assertTrue("must start with 'alarmas.'" in failures.single())
    }

    @Test
    fun `a mismatched value reports the json path`() {
        val fixture = fixture("fake.count", buildJsonObject { put("count", 99) })
        val failures = ConformanceRunner.run(registry, listOf(fixture))

        assertEquals(1, failures.size)
        assertTrue("\$.count" in failures.single())
    }
}
