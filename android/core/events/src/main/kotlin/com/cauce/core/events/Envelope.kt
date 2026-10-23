package com.cauce.core.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The one shape every event in the log has. See `schema/envelope.v1.json`.
 *
 * [serverSeq] is assigned by the server on receipt and only present on pulled events; it is not
 * part of what a device produces.
 */
@Serializable
data class Envelope(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val type: String,
    val v: Int,
    val ts: String,
    val payload: JsonObject,
    @SerialName("server_seq") val serverSeq: Long? = null,
)
