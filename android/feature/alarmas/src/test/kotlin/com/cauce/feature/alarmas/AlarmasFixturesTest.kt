package com.cauce.feature.alarmas

import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import com.cauce.core.events.projection.ProjectionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmasFixturesTest {
    @Test
    fun `all alarmas fixtures pass on the jvm`() {
        val schemaDir = File(
            System.getProperty("cauce.schemaDir")
                ?: error("Missing -Dcauce.schemaDir"),
        )
        val fixturesDir = schemaDir.resolve("features/alarmas/fixtures")
        val files = fixturesDir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()
        assertEquals(16, files.size, "expected 16 alarmas fixtures, found ${files.map { it.name }}")

        val registry = ProjectionRegistry(AlarmasFeatureModule().projections)
        val failures = files.mapNotNull { file ->
            val root = CauceJson.wire.parseToJsonElement(file.readText()).jsonObject
            val key = root.getValue("projection").jsonPrimitive.content
            val params = root["params"]?.jsonObject ?: JsonObject(emptyMap())
            val events = (root.getValue("events") as JsonArray).map { decodeEvent(it.jsonObject) }
            val expected = root.getValue("expected")
            try {
                val actual = registry.compute(key, events, params)
                if (actual != expected) {
                    "${file.path}: expected $expected got $actual"
                } else {
                    null
                }
            } catch (e: Exception) {
                "${file.path}: ${e::class.simpleName}: ${e.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun decodeEvent(raw: JsonObject): Envelope {
        val fields = raw.toMutableMap()
        if ("user_id" !in fields) fields["user_id"] = JsonPrimitive("u1")
        if ("device_id" !in fields) fields["device_id"] = JsonPrimitive("test")
        return CauceJson.wire.decodeFromJsonElement(Envelope.serializer(), JsonObject(fields))
    }
}
