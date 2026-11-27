package dev.cloudwind.core

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per-object state as the Worker reports it. */
@Serializable
data class ObjectStatus(
    val id: String,
    val state: String, // "uploaded" | "synced"
    val size: Long,
    val uploaded_at: Long,
    val synced_at: Long? = null,
)

@Serializable
data class StatusResponse(val objects: List<ObjectStatus>, val inbox_bytes: Long = 0)

class HttpError(val code: Int, message: String) : IOException("HTTP $code: $message")

/** Phone-side client for the Cloudwind Worker. Uses only java.net so it runs anywhere. */
class WorkerClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** Single PUT up to this size; multipart above. Matches the Rust agent. */
        const val SINGLE_MAX = 64L shl 20
        const val PART_SIZE = 8L shl 20
        val ID = Regex("^[0-9a-f]{32}$")

        fun newObjectId(random: java.security.SecureRandom = java.security.SecureRandom()): String {
            val b = ByteArray(16).also { random.nextBytes(it) }
            return Crypto.hex(b)
        }
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("authorization", "Bearer $token")
        return c
    }

    private fun body(c: HttpURLConnection, okCodes: Set<Int>): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in okCodes) throw HttpError(code, text.take(200))
        return text
    }

    private fun putStream(path: String, file: File, offset: Long, len: Long, ok: Set<Int>): String {
        val c = open("PUT", path)
        c.doOutput = true
        c.setFixedLengthStreamingMode(len) // streams from disk; never buffers the part in memory
        FileInputStream(file).use { fin ->
            fin.channel.position(offset)
            c.outputStream.use { os ->
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("file shorter than expected")
                    os.write(buf, 0, n); left -= n
                }
            }
        }
        return body(c, ok)
    }

    /** Checks the URL and token without uploading anything. */
    fun checkAccess(): Boolean = try {
        val c = open("GET", "/v1/status?since=${Long.MAX_VALUE / 2}")
        c.responseCode == 200
    } catch (e: IOException) {
        false
    }

    /**
     * Upload an already-encrypted file under `id`. A 409 means a previous attempt
     * already landed, which counts as success.
     */
    fun upload(id: String, file: File) {
        require(ID.matches(id)) { "bad id" }
        val size = file.length()
        if (size <= SINGLE_MAX) {
            try { putStream("/v1/objects/$id", file, 0, size, setOf(201)) } catch (e: HttpError) { if (e.code != 409) throw e }
            return
        }
        val c = open("POST", "/v1/objects/$id/multipart")
        val up = try {
            val text = body(c, setOf(201))
            (json.parseToJsonElement(text) as JsonObject)["uploadId"]!!.let { (it as JsonPrimitive).content }
        } catch (e: HttpError) {
            if (e.code == 409) return else throw e
        }
        val parts = mutableListOf<JsonObject>()
        var n = 1; var off = 0L
        while (off < size) {
            val len = minOf(PART_SIZE, size - off)
            val text = putStream("/v1/objects/$id/multipart/$up/$n", file, off, len, setOf(200))
            parts += json.parseToJsonElement(text) as JsonObject
            n++; off += len
        }
        val done = open("POST", "/v1/objects/$id/multipart/$up/complete")
        done.doOutput = true
        done.setRequestProperty("content-type", "application/json")
        val payload = buildJsonObject { put("parts", buildJsonArray { parts.forEach { add(it) } }) }.toString().toByteArray()
        done.setFixedLengthStreamingMode(payload.size)
        done.outputStream.use { it.write(payload) }
        try { body(done, setOf(201)) } catch (e: HttpError) { if (e.code != 409) throw e }
    }

    fun status(since: Long = 0): StatusResponse {
        val c = open("GET", "/v1/status?since=$since")
        return json.decodeFromString(StatusResponse.serializer(), body(c, setOf(200)))
    }
}
