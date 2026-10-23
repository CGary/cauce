package com.cauce.core.events

import kotlinx.serialization.json.Json

/**
 * JSON codecs used across the event log.
 *
 * [wire] is for envelopes travelling to/from the server: absent optional fields stay absent and
 * nulls are omitted, so a local event never sends `server_seq: null` (the envelope schema wants an
 * integer when the key is present).
 *
 * [output] is for projections: every field is encoded, including explicit nulls, so the JSON has
 * exactly the shape the fixtures compare against.
 */
object CauceJson {
    val wire = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    val output = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
}
