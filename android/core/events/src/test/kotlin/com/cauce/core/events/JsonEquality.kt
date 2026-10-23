package com.cauce.core.events

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Structural JSON comparison for conformance fixtures: object key order is irrelevant, array order
 * is relevant and numbers compare with a 1e-9 tolerance. Returns the first difference, or `null`.
 */
object JsonEquality {
    private val TOLERANCE = BigDecimal("1e-9")

    fun firstDifference(expected: JsonElement, actual: JsonElement, path: String = "$"): String? = when {
        expected is JsonNull && actual is JsonNull -> null

        expected is JsonObject && actual is JsonObject -> {
            val missing = expected.keys - actual.keys
            val extra = actual.keys - expected.keys
            when {
                missing.isNotEmpty() -> "$path: missing keys ${missing.sorted()}"
                extra.isNotEmpty() -> "$path: unexpected keys ${extra.sorted()}"
                else -> expected.keys.sorted().firstNotNullOfOrNull { key ->
                    firstDifference(expected.getValue(key), actual.getValue(key), "$path.$key")
                }
            }
        }

        expected is JsonArray && actual is JsonArray -> when {
            expected.size != actual.size -> "$path: expected an array of ${expected.size}, got ${actual.size}"
            else -> (0 until expected.size).firstNotNullOfOrNull { index ->
                firstDifference(expected[index], actual[index], "$path[$index]")
            }
        }

        expected is JsonPrimitive && actual is JsonPrimitive -> comparePrimitives(expected, actual, path)

        else -> "$path: expected ${expected.describe()}, got ${actual.describe()}"
    }

    private fun comparePrimitives(expected: JsonPrimitive, actual: JsonPrimitive, path: String): String? {
        if (expected.isString || actual.isString) {
            return if (expected.isString && actual.isString && expected.content == actual.content) {
                null
            } else {
                "$path: expected ${expected.describe()}, got ${actual.describe()}"
            }
        }

        if (expected.content == actual.content) return null

        val expectedNumber = expected.content.toBigDecimalOrNull()
        val actualNumber = actual.content.toBigDecimalOrNull()
        if (expectedNumber == null || actualNumber == null) {
            return "$path: expected ${expected.describe()}, got ${actual.describe()}"
        }

        return if ((expectedNumber - actualNumber).abs() <= TOLERANCE) {
            null
        } else {
            "$path: expected ${expected.describe()}, got ${actual.describe()}"
        }
    }

    private fun JsonElement.describe(): String = when (this) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (isString) "\"$content\"" else content
        else -> toString()
    }
}
