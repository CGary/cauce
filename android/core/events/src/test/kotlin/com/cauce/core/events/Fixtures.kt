package com.cauce.core.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * A fixture file: `schema/features/<feature>/fixtures/<projection>/<case>.json`.
 *
 * `user_id` and `device_id` are optional in fixtures; the runners apply the same defaults as
 * `schema/README.md`.
 */
@Serializable
data class Fixture(
    val projection: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val events: List<JsonObject>,
    val expected: JsonElement,
)

data class FixtureFile(
    val path: File,
    val featureId: String,
    val fixture: Fixture,
)

object Fixtures {
    const val DEFAULT_USER_ID = "u1"
    const val DEFAULT_DEVICE_ID = "test"

    fun schemaDir(): File {
        val configured = System.getProperty("cauce.schemaDir")
            ?: error("Missing -Dcauce.schemaDir=<repo>/schema (the Gradle test task sets it)")
        return File(configured).also {
            check(it.isDirectory) { "cauce.schemaDir is not a directory: $it" }
        }
    }

    fun loadAll(schemaDir: File = schemaDir()): List<FixtureFile> {
        val featuresDir = schemaDir.resolve("features")
        check(featuresDir.isDirectory) { "No features directory at $featuresDir" }

        return featuresDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("_") }
            .sortedBy { it.name }
            .flatMap { featureDir ->
                featureDir.resolve("fixtures")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "json" }
                    .sortedBy { it.path }
                    .map { path -> load(path, featureDir.name) }
                    .toList()
            }
    }

    fun load(path: File, featureId: String): FixtureFile =
        try {
            val element = CauceJson.wire.parseToJsonElement(path.readText())
            FixtureFile(
                path = path,
                featureId = featureId,
                fixture = CauceJson.wire.decodeFromJsonElement<Fixture>(element),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Cannot read fixture ${path.path}: ${e.message}", e)
        }

    fun decodeEvents(raw: List<JsonObject>): List<Envelope> = raw.map { element ->
        val fields = element.toMutableMap()
        if ("user_id" !in fields) fields["user_id"] = JsonPrimitive(DEFAULT_USER_ID)
        if ("device_id" !in fields) fields["device_id"] = JsonPrimitive(DEFAULT_DEVICE_ID)
        CauceJson.wire.decodeFromJsonElement<Envelope>(JsonObject(fields))
    }

    fun eventsOf(fixture: Fixture): List<Envelope> = decodeEvents(fixture.events)
}
