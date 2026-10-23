package com.cauce.core.events.validation

import android.content.res.AssetManager
import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.FileNotFoundException
import java.io.InputStream

/** Where the JSON Schemas come from: debug assets on device, a directory in JVM tests. */
fun interface SchemaSource {
    /** Returns `null` when there is no schema at [path]. */
    fun open(path: String): InputStream?
}

class AssetSchemaSource(private val assets: AssetManager) : SchemaSource {
    override fun open(path: String): InputStream? =
        try {
            assets.open(path)
        } catch (_: FileNotFoundException) {
            null
        }
}

/**
 * Debug-only schema validator. The contract (`schema/`) is copied into the debug assets by the
 * `copyDebugSchemas` Gradle task, so the APK cannot drift from it and release builds do not pay
 * for validation.
 */
class SchemaEventValidator(private val source: SchemaSource) : EventValidator {
    constructor(assets: AssetManager) : this(AssetSchemaSource(assets))

    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val cache = mutableMapOf<String, JsonSchema?>()

    override fun validate(event: Envelope): List<String> {
        val errors = mutableListOf<String>()

        val envelopeJson = CauceJson.wire.encodeToJsonElement(Envelope.serializer(), event)
        errors += validateAgainst(ENVELOPE_PATH, envelopeJson.toString(), "envelope")

        val payloadPath = "$EVENTS_DIR/${event.type}.v${event.v}.json"
        errors += validateAgainst(payloadPath, event.payload.toString(), "payload")

        return errors
    }

    @Synchronized
    private fun schema(path: String): JsonSchema? = cache.getOrPut(path) {
        source.open(path)?.use { stream -> load(path, stream) }
    }

    private fun load(path: String, stream: InputStream): JsonSchema {
        val node = mapper.readTree(stream) as? ObjectNode
            ?: error("Schema $path is not a JSON object")

        // The contract uses relative $id values (e.g. "cauce/envelope.v1.json"). The 2020-12
        // validator wants an absolute URI, so anchor each self-contained schema to a synthetic one.
        node.put("\$id", "https://cauce.local/schema/$path")
        return factory.getSchema(node)
    }

    private fun validateAgainst(path: String, json: String, label: String): List<String> {
        val schema = schema(path) ?: return listOf("$label: no JSON Schema at $path")
        return schema.validate(mapper.readTree(json)).map { "$label: ${it.message}" }
    }

    private companion object {
        const val ENVELOPE_PATH = "envelope.v1.json"
        const val EVENTS_DIR = "events"
    }
}
