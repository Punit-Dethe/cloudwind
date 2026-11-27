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

/** Resumable multipart uploads, against a fake Worker that records which parts it received. */
class ResumeTest {
    private val partsSeen = mutableListOf<Int>()
    private var startCount = 0
    private var failPart: Int? = null
    private var staleUploadId: String? = null
    private var completeBody = ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes()
            fun reply(code: Int, text: String) {
                val b = text.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            when {
                path.endsWith("/multipart") -> { startCount++; reply(201, """{"id":"x","uploadId":"U$startCount"}""") }
                path.endsWith("/complete") -> { completeBody = String(body); reply(201, """{"id":"x"}""") }
                else -> {
                    val upload = path.substringAfter("/multipart/").substringBefore("/")
                    val n = path.substringAfterLast("/").toInt()
                    when {
                        upload == staleUploadId -> reply(404, """{"error":"unknown_upload"}""")
                        n == failPart -> { failPart = null; reply(500, "boom") }
                        else -> { partsSeen += n; reply(200, """{"partNumber":$n,"etag":"e$n"}""") }
                    }
                }
            }
        }
        start()
    }
    private val client get() = WorkerClient("http://127.0.0.1:${server.address.port}", "tok")
    private val big: File = File.createTempFile("cwbig", ".bin").also {
        // 64 MiB + 1 byte -> 9 parts of 8 MiB (last is 1 byte). Sparse-ish content is fine for the test.
        java.io.RandomAccessFile(it, "rw").use { f -> f.setLength(WorkerClient.SINGLE_MAX + 1) }
        it.deleteOnExit()
    }
    private val id = WorkerClient.newObjectId()

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun interrupted_upload_resumes_without_resending_finished_parts() {
        var saved: ResumeState? = null
        failPart = 4
        assertFailsWith<HttpError> { client.upload(id, big, onState = { saved = it }) }
        assertEquals(listOf(1, 2, 3), partsSeen)
        assertEquals(3, saved!!.parts.size)

        partsSeen.clear()
        client.upload(id, big, resume = ResumeState.fromJson(saved!!.toJson()), onState = { saved = it })
        assertEquals((4..9).toList(), partsSeen, "only the remaining parts are sent")
        assertEquals(1, startCount, "no second upload was started")
        assertEquals(null, saved, "state cleared when done")
        (1..9).forEach { assertTrue(completeBody.contains("\"partNumber\":$it"), "part $it in complete") }
    }

    @Test
    fun stop_request_pauses_between_parts_with_progress_saved() {
        var saved: ResumeState? = null
        var calls = 0
        assertFailsWith<UploadInterrupted> { client.upload(id, big, onState = { saved = it }, shouldStop = { ++calls > 2 }) }
        assertEquals(2, saved!!.parts.size)
        assertEquals(listOf(1, 2), partsSeen)
    }

    @Test
    fun upload_the_server_forgot_starts_over() {
        staleUploadId = "OLD"
        client.upload(id, big, resume = ResumeState("OLD", listOf(UploadedPart(1, "e1"), UploadedPart(2, "e2"))))
        assertEquals(1, startCount, "a fresh upload was started")
        assertEquals((1..9).toList(), partsSeen)
    }

    @Test
    fun garbage_resume_state_is_ignored() {
        client.upload(id, big, resume = ResumeState("U9", listOf(UploadedPart(5, "x")))) // not contiguous
        assertEquals(1, startCount)
        assertEquals((1..9).toList(), partsSeen)
    }

    @Test
    fun saved_state_survives_json_roundtrip_and_bad_json_is_null() {
        val s = ResumeState("U1", listOf(UploadedPart(1, "a")))
        assertEquals(s, ResumeState.fromJson(s.toJson()))
        assertEquals(null, ResumeState.fromJson("{nope"))
        assertEquals(null, ResumeState.fromJson(null))
    }
}
