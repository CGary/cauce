package com.cauce.core.sync

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpSyncApiTest {
    private val token = "test-token-0123456789abcdefghijklmnop" // invented
    private val seen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: ServerSocket
    @Volatile private var status = 200

    @BeforeTest
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { serve(it) }
            }
        }
    }

    /** One request per connection: enough HTTP/1.1 for HttpURLConnection. */
    private fun serve(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val target = input.readLine().orEmpty().split(" ").getOrElse(1) { "" }
        val path = target.substringBefore('?')
        if ('?' in target) queries += target.substringAfter('?')
        var auth: String? = null
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val (name, value) = line.split(":", limit = 2).map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
            if (name.equals("Authorization", ignoreCase = true)) auth = value
            if (name.equals("Content-Length", ignoreCase = true)) length = value.toInt()
        }
        repeat(length) { input.read() }
        seen += path to auth
        val body = when {
            status != 200 -> """{"error":"falta el token"}"""
            path == "/sync/pull" -> """{"events":[],"next_since":0}"""
            path == "/sync/push" -> """{"accepted":0,"duplicates":0}"""
            else -> """{"url":"https://storage.example/put"}"""
        }.toByteArray()
        val out = socket.getOutputStream()
        out.write(
            "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    @AfterTest
    fun stop() {
        server.close()
        SyncFeedback.succeeded()
    }

    private fun api() = HttpSyncApi("http://127.0.0.1:${server.localPort}/", apiToken = token)

    @Test
    fun `every server call carries the bearer token`() = runBlocking {
        val api = api()
        api.pull(0)
        api.push(emptyList())
        api.presign("u1/a.m4a", "audio/mp4")

        assertEquals(listOf("/sync/pull", "/sync/push", "/blobs/presign"), seen.map { it.first })
        assertTrue(seen.all { it.second == "Bearer $token" }, "headers: $seen")
    }

    @Test
    fun `a 401 becomes a readable sync problem, not a silent failure`() = runBlocking {
        status = 401
        val error = assertFailsWith<SyncHttpException> { api().pull(0) }
        assertEquals(401, error.code)

        SyncFeedback.failed(error)
        val message = assertNotNull(SyncFeedback.problem.value)
        assertTrue("token" in message && "401" in message, message)

        SyncFeedback.succeeded()
        assertNull(SyncFeedback.problem.value)
    }

    @Test
    fun `transient failures do not replace the message`() {
        SyncFeedback.failed(SyncHttpException(401, ""))
        val before = SyncFeedback.problem.value
        SyncFeedback.failed(SyncHttpException(503, ""))
        SyncFeedback.failed(IOException("offline"))
        assertEquals(before, SyncFeedback.problem.value)
        assertNull(SyncFeedback.messageFor(SyncHttpException(429, "")))
        assertNotNull(SyncFeedback.messageFor(SyncHttpException(400, "")))
    }

    @Test
    fun `the pull URL carries the device id, URL-encoded`() {
        assertEquals("http://h/sync/pull?since=7", pullUrl("http://h/", 7, null))
        assertEquals("http://h/sync/pull?since=0&device_id=RMX5070", pullUrl("http://h", 0, "RMX5070"))
        assertEquals(
            "http://h/sync/pull?since=42&device_id=Pixel%207%20Pro%26x%3D1%2Fy",
            pullUrl("http://h/", 42, "Pixel 7 Pro&x=1/y"),
        )
    }

    @Test
    fun `pull sends device_id to the server`() = runBlocking {
        HttpSyncApi("http://127.0.0.1:${server.localPort}", apiToken = token, deviceId = "Pixel 7").pull(3)
        assertEquals("since=3&device_id=Pixel%207", queries.single())
    }

    @Test
    fun `push tolerates server_seq and unknown fields`() {
        val decoded = com.cauce.core.events.CauceJson.wire.decodeFromString(
            PushResponse.serializer(),
            """{"accepted":2,"duplicates":1,"server_seq":412,"extra":true}""",
        )
        assertEquals(PushResponse(accepted = 2, duplicates = 1, serverSeq = 412), decoded)
    }

    @Test
    fun `a blank token is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { HttpSyncApi("http://127.0.0.1:1", apiToken = " ") }
    }
}
