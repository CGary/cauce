package com.cauce.core.events

import com.cauce.core.events.projection.Projection
import com.cauce.core.events.projection.ProjectionRegistry
import com.cauce.core.events.projection.UnknownProjectionException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProjectionRegistryTest {
    @Serializable
    private data class Output(val count: Int, val now: String? = null)

    private fun projection(key: String = "fake.count"): Projection<Output> =
        object : Projection<Output> {
            override val key = key
            override val eventTypes = setOf("fake_event")
            override val serializer: KSerializer<Output> = Output.serializer()
            override fun compute(events: List<Envelope>, params: JsonObject): Output =
                Output(events.size, params["now"]?.jsonPrimitive?.content)
        }

    private fun event(id: String, type: String): Envelope = Envelope(
        id = id,
        userId = "u1",
        deviceId = "test",
        type = type,
        v = 1,
        ts = "2026-10-22T10:00:00Z",
        payload = JsonObject(emptyMap()),
    )

    @Test
    fun `compute filters events by declared types and passes params`() {
        val registry = ProjectionRegistry(listOf(projection()))
        val events = listOf(event("e1", "fake_event"), event("e2", "other_event"))
        val params = buildJsonObject { put("now", "2026-10-22T12:00:00Z") }

        assertEquals(
            """{"count":1,"now":"2026-10-22T12:00:00Z"}""",
            registry.compute("fake.count", events, params).toString(),
        )
    }

    @Test
    fun `unknown projection throws`() {
        val registry = ProjectionRegistry(listOf(projection()))
        assertFailsWith<UnknownProjectionException> {
            registry.compute("fake.missing", emptyList(), JsonObject(emptyMap()))
        }
        assertNull(registry["fake.missing"])
    }

    @Test
    fun `duplicate keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectionRegistry(listOf(projection(), projection()))
        }
    }

    @Test
    fun `malformed keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectionRegistry(listOf(projection(key = "not-a-key")))
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectionRegistry(listOf(projection(key = "feature.")))
        }
    }

    @Test
    fun `projection with no event types is rejected`() {
        val empty = object : Projection<Output> {
            override val key = "fake.empty"
            override val eventTypes = emptySet<String>()
            override val serializer: KSerializer<Output> = Output.serializer()
            override fun compute(events: List<Envelope>, params: JsonObject) = Output(0)
        }
        assertFailsWith<IllegalArgumentException> { ProjectionRegistry(listOf(empty)) }
    }

    @Test
    fun `aggregate collects every module`() {
        val module = object : FeatureModule {
            override val id = "fake"
            override val eventTypes = setOf("fake_event")
            override val projections: List<Projection<*>> = listOf(projection())
            override fun androidx.navigation.NavGraphBuilder.navGraph(nav: androidx.navigation.NavController) = Unit
        }
        assertEquals(setOf("fake.count"), ProjectionRegistry.aggregate(listOf(module)).keys())
    }
}
