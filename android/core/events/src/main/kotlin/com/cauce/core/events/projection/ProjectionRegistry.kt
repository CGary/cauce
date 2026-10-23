package com.cauce.core.events.projection

import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import com.cauce.core.events.FeatureModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

class UnknownProjectionException(key: String) : IllegalArgumentException("Unknown projection: $key")

/**
 * The single dispatch table for projections, keyed by `<feature>.<name>`. Both the fixtures and the
 * UI go through it, so a projection that passes conformance is the one the app actually renders.
 */
class ProjectionRegistry(projections: List<Projection<*>> = emptyList()) {
    private val byKey: Map<String, Projection<*>>

    init {
        projections.forEach { projection ->
            require(KEY_PATTERN.matches(projection.key)) {
                "Projection key '${projection.key}' must match <feature>.<name>"
            }
            require(projection.eventTypes.isNotEmpty()) {
                "Projection '${projection.key}' declares no event types"
            }
        }
        byKey = projections.associateBy { it.key }
        require(byKey.size == projections.size) {
            val duplicates = projections.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
            "Duplicate projection keys: ${duplicates.sorted()}"
        }
    }

    fun keys(): Set<String> = byKey.keys

    operator fun get(key: String): Projection<*>? = byKey[key]

    fun compute(key: String, events: List<Envelope>, params: JsonObject): JsonElement {
        val projection = byKey[key] ?: throw UnknownProjectionException(key)
        return computeTyped(projection, events, params)
    }

    private fun <T> computeTyped(
        projection: Projection<T>,
        events: List<Envelope>,
        params: JsonObject,
    ): JsonElement {
        val relevant = events.filter { it.type in projection.eventTypes }
        return CauceJson.output.encodeToJsonElement(projection.serializer, projection.compute(relevant, params))
    }

    companion object {
        private val KEY_PATTERN = Regex("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$")

        fun aggregate(modules: List<FeatureModule>): ProjectionRegistry =
            ProjectionRegistry(modules.flatMap { it.projections })
    }
}
