package dev.cloudwind.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkerClientTest {
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private var handler: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1); it.close() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            bodies += ex.requestBody.readBytes().let { if (it.size < 200) String(it) else "<${it.size} bytes>" }
            handler(ex)
        }
        start()
    }
    private val client get() = WorkerClient("http://127.0.0.1:${server.address.port}", "tok")

    @AfterTest fun stop() = server.stop(0)

    private fun reply(ex: HttpExchange, code: Int, body: String, vararg headers: Pair<String, String>) {
        val b = body.toByteArray()
        headers.forEach { ex.responseHeaders.add(it.first, it.second) }
        ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }

    private fun file(size: Int) = File.createTempFile("cwtest", ".bin").also { it.writeBytes(ByteArray(size) { 1 }); it.deleteOnExit() }

    @Test
    fun inbox_full_exposes_free_bytes_and_reason() {
        handler = { reply(it, 507, """{"error":"inbox_full","free_bytes":123456,"cap_bytes":999}""") }
        val e = assertFailsWith<HttpError> { client.upload(WorkerClient.newObjectId(), file(100)) }
        assertTrue(e.isInboxFull)
        assertEquals("inbox_full", e.reason)
        assertEquals(123456L, e.freeBytes)
    }

    @Test
    fun too_large_and_rate_limited_are_distinguishable() {
        handler = { reply(it, 413, """{"error":"object_too_large","max_object_bytes":5}""") }
        assertTrue(assertFailsWith<HttpError> { client.upload(WorkerClient.newObjectId(), file(10)) }.isTooLarge)

        handler = { reply(it, 429, """{"error":"rate_limited"}""", "retry-after" to "30") }
        val e = assertFailsWith<HttpError> { client.upload(WorkerClient.newObjectId(), file(10)) }
        assertTrue(e.isRateLimited)
        assertEquals(30, e.retryAfterSecs)
    }

    @Test
    fun conflict_means_already_uploaded() {
        handler = { reply(it, 409, """{"error":"already uploaded"}""") }
        client.upload(WorkerClient.newObjectId(), file(10)) // no exception
    }

    @Test
    fun multipart_start_declares_total_size() {
        val size = (WorkerClient.SINGLE_MAX + 1).toInt() // forces multipart
        handler = { ex ->
            when {
                ex.requestURI.path.endsWith("/multipart") -> reply(ex, 201, """{"id":"x","uploadId":"U1"}""")
                ex.requestURI.path.endsWith("/complete") -> reply(ex, 201, """{"id":"x"}""")
                else -> reply(ex, 200, """{"partNumber":1,"etag":"e"}""")
            }
        }
        client.upload(WorkerClient.newObjectId(), file(size))
        val startIdx = requests.indexOfFirst { it.startsWith("POST") && it.endsWith("/multipart") }
        assertEquals("""{"size":$size}""", bodies[startIdx])
        assertTrue(requests.any { it.endsWith("/multipart/U1/complete") })
    }

    @Test
    fun status_parses_limits() {
        handler = { reply(it, 200, """{"objects":[],"inbox_bytes":5,"limits":{"cap_bytes":10,"soft_bytes":6,"free_bytes":5,"max_object_bytes":7}}""") }
        val l = client.limits()
        assertEquals(5L, l.free_bytes)
        assertEquals(7L, l.max_object_bytes)
    }
}

class SourceChangedTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun file_that_grows_between_passes_is_refused() {
        var opens = 0
        val result = runCatching {
            Crypto.encrypt(key, "v.mp4", null, {
                opens++
                ByteArrayInputStream(ByteArray(if (opens == 1) 1000 else 1500) { 3 }) // grew after hashing
            }, ByteArrayOutputStream(), 64)
        }
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is Crypto.SourceChangedException)
    }

    @Test
    fun stable_file_is_fine() {
        Crypto.encrypt(key, "v.mp4", null, { ByteArrayInputStream(ByteArray(1000) { 3 }) }, ByteArrayOutputStream(), 64)
    }
}
