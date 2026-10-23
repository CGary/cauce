package com.cauce.core.events

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonEqualityTest {
    @Test
    fun `object key order is irrelevant`() {
        val expected = buildJsonObject {
            put("a", 1)
            put("b", "two")
        }
        val actual = buildJsonObject {
            put("b", "two")
            put("a", 1)
        }
        assertNull(JsonEquality.firstDifference(expected, actual))
    }

    @Test
    fun `array order is relevant`() {
        val expected = buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }
        val actual = buildJsonArray { add(JsonPrimitive(2)); add(JsonPrimitive(1)) }
        assertNotNull(JsonEquality.firstDifference(expected, actual))
    }

    @Test
    fun `numbers compare with a 1e-9 tolerance`() {
        val expected = buildJsonObject { put("dosis", 15) }
        val actual = buildJsonObject { put("dosis", 15.0000000001) }
        assertNull(JsonEquality.firstDifference(expected, actual))

        val off = buildJsonObject { put("dosis", 15.000001) }
        assertNotNull(JsonEquality.firstDifference(expected, off))
    }

    @Test
    fun `missing null key is a difference and null matches null`() {
        val expected = buildJsonObject { put("ultima_dosis", JsonNull) }
        val missing = buildJsonObject { }
        val nullValue = buildJsonObject { put("ultima_dosis", JsonNull) }

        assertNotNull(JsonEquality.firstDifference(expected, missing))
        assertNull(JsonEquality.firstDifference(expected, nullValue))
    }

    @Test
    fun `string and number primitives are not interchangeable`() {
        val difference = JsonEquality.firstDifference(JsonPrimitive(1), JsonPrimitive("1"))
        assertNotNull(difference)
        assertEquals("$: expected 1, got \"1\"", difference)
    }

    @Test
    fun `extra keys are reported`() {
        val expected = buildJsonObject { put("a", 1) }
        val actual = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        assertEquals("$: unexpected keys [b]", JsonEquality.firstDifference(expected, actual))
    }
}
