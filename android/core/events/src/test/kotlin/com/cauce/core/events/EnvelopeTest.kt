package com.cauce.core.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvelopeTest {
    private val event = Envelope(
        id = "0192e4a1-7f3c-7a2b-9c1d-4e5f6a7b8c9d",
        userId = "u1",
        deviceId = "pixel-1",
        type = "dosis_dada",
        v = 1,
        ts = "2026-10-22T13:05:00Z",
        payload = buildJsonObject { put("alarma_id", "a1") },
    )

    @Test
    fun `wire encoding omits server_seq and nulls`() {
        val json = CauceJson.wire.encodeToString(Envelope.serializer(), event)
        assertFalse("server_seq" in json)
        assertFalse("null" in json)
        assertTrue("\"user_id\":\"u1\"" in json)
        assertTrue("\"device_id\":\"pixel-1\"" in json)
    }

    @Test
    fun `decoding ignores unknown envelope fields`() {
        val element = CauceJson.wire.parseToJsonElement(
            """
            {
              "id": "e1",
              "user_id": "u1",
              "device_id": "web",
              "type": "alarma_creada",
              "v": 1,
              "ts": "2026-10-22T13:05:00Z",
              "payload": {"alarma_id": "a1"},
              "future_field": 42
            }
            """.trimIndent(),
        )

        val decoded = CauceJson.wire.decodeFromJsonElement(Envelope.serializer(), element)
        assertEquals("web", decoded.deviceId)
        assertEquals("a1", decoded.payload.getValue("alarma_id").jsonPrimitive.content)
    }

    @Test
    fun `server_seq survives a pull round trip`() {
        val pulled = event.copy(serverSeq = 17L)
        val json = CauceJson.wire.encodeToString(Envelope.serializer(), pulled)
        val decoded = CauceJson.wire.decodeFromString(Envelope.serializer(), json)
        assertEquals(17L, decoded.serverSeq)
    }

    @Test
    fun `payload is preserved verbatim`() {
        val encoded: JsonObject = CauceJson.wire.encodeToJsonElement(Envelope.serializer(), event).jsonObject
        assertEquals(
            event.payload,
            encoded.getValue("payload").jsonObject,
        )
    }
}
