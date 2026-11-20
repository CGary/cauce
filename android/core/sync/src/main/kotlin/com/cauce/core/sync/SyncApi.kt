package com.cauce.core.sync

import com.cauce.core.events.CauceJson
import com.cauce.core.events.Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class PushRequest(val events: List<Envelope>)

@Serializable
data class PushResponse(val accepted: Int = 0, val duplicates: Int = 0)

@Serializable
data class PullResponse(
    val events: List<Envelope> = emptyList(),
    @SerialName("next_since") val nextSince: Long = 0,
)

@Serializable
data class PresignRequest(
    val key: String,
    @SerialName("content_type") val contentType: String,
)

@Serializable
data class PresignResponse(val url: String = "")

class SyncHttpException(val code: Int, val body: String) :
    IOException("Sync request failed: HTTP $code ${body.take(500)}")

/** The three calls the phone makes against the server. */
interface SyncApi {
    suspend fun push(events: List<Envelope>): PushResponse

    suspend fun pull(since: Long): PullResponse

    suspend fun presign(key: String, contentType: String): PresignResponse
}

/**
 * Talks to the Go server directly. Every call carries `Authorization: Bearer <apiToken>` (D20);
 * the presigned PUT to object storage does not, because it goes to another host.
 */
class HttpSyncApi(
    baseUrl: String,
    apiToken: String,
    extraHeaders: Map<String, String> = emptyMap(),
) : SyncApi {
    private val base = baseUrl.trimEnd('/')
    private val headers: Map<String, String> = extraHeaders + authHeaders(apiToken)

    override suspend fun push(events: List<Envelope>): PushResponse {
        val body = CauceJson.wire.encodeToString(PushRequest.serializer(), PushRequest(events))
        val response = HttpTransport.request("POST", "$base/sync/push", body, headers)
        return CauceJson.wire.decodeFromString(PushResponse.serializer(), response)
    }

    override suspend fun pull(since: Long): PullResponse {
        val response = HttpTransport.request("GET", "$base/sync/pull?since=$since", null, headers)
        return CauceJson.wire.decodeFromString(PullResponse.serializer(), response)
    }

    override suspend fun presign(key: String, contentType: String): PresignResponse {
        val body = CauceJson.wire.encodeToString(
            PresignRequest.serializer(),
            PresignRequest(key = key, contentType = contentType),
        )
        val response = HttpTransport.request("POST", "$base/blobs/presign", body, headers)
        return CauceJson.wire.decodeFromString(PresignResponse.serializer(), response)
    }
}

/** The header every server call carries. A blank token is a wiring bug, not a runtime state. */
internal fun authHeaders(apiToken: String): Map<String, String> {
    require(apiToken.isNotBlank()) { "apiToken is blank: build the APK with -PcauceApiToken (D20)" }
    return mapOf("Authorization" to "Bearer ${apiToken.trim()}")
}

internal object HttpTransport {
    suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw SyncHttpException(code, text)
            text
        } finally {
            connection.disconnect()
        }
    }

    suspend fun putFile(url: String, file: File, contentType: String) = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", contentType)
        }

        try {
            connection.outputStream.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw SyncHttpException(code, connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Presign, then PUT the local file straight to object storage. */
class BlobUploader(private val api: SyncApi) {
    suspend fun upload(key: String, contentType: String, file: File): Boolean {
        if (!file.exists()) return false
        val presigned = api.presign(key, contentType)
        HttpTransport.putFile(presigned.url, file, contentType)
        return true
    }
}
