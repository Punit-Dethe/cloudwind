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

/** One finished multipart part, as the Worker returned it. */
@Serializable
data class UploadedPart(val partNumber: Int, val etag: String)

/**
 * Progress of a multipart upload, saved by the caller after every part so a killed or
 * interrupted run continues instead of starting over. Part size is fixed (R2 requires equal
 * parts), so part N always starts at byte (N-1) * PART_SIZE.
 */
@Serializable
data class ResumeState(val uploadId: String, val parts: List<UploadedPart> = emptyList()) {
    fun toJson(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun fromJson(s: String?): ResumeState? =
            if (s.isNullOrBlank()) null else try { Json.decodeFromString(serializer(), s) } catch (e: Exception) { null }
    }
}

/** The caller asked us to stop (e.g. the OS is stopping the job). Progress so far was saved. */
class UploadInterrupted : IOException("upload paused")

/** Inbox limits as configured on the Worker. */
@Serializable
data class Limits(
    val cap_bytes: Long = 0,
    val soft_bytes: Long = 0,
    val free_bytes: Long = Long.MAX_VALUE,
    val max_object_bytes: Long = Long.MAX_VALUE,
)

@Serializable
data class StatusResponse(val objects: List<ObjectStatus>, val inbox_bytes: Long = 0, val limits: Limits = Limits())

/**
 * A non-success reply. `reason` is the Worker's machine-readable error ("inbox_full",
 * "object_too_large", "rate_limited", ...). `freeBytes` is set when the inbox is full.
 */
class HttpError(
    val code: Int,
    message: String,
    val reason: String? = null,
    val freeBytes: Long? = null,
    val retryAfterSecs: Int? = null,
) : IOException("HTTP $code: $message") {
    val isInboxFull get() = code == 507
    val isTooLarge get() = code == 413
    val isRateLimited get() = code == 429
}

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
        if (code !in okCodes) {
            val obj = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            fun field(n: String) = (obj?.get(n) as? JsonPrimitive)?.content
            throw HttpError(
                code, text.take(200), reason = field("error"),
                freeBytes = field("free_bytes")?.toLongOrNull(),
                retryAfterSecs = c.getHeaderField("retry-after")?.toIntOrNull() ?: field("retry_after")?.toIntOrNull(),
            )
        }
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
     * Upload an already-encrypted file under `id`. A 409 means a previous attempt already
     * landed, which counts as success.
     *
     * Large files go up in fixed 8 MiB parts. `onState` is called with the progress after every
     * part (and with null when finished or discarded) so the caller can persist it; pass the saved
     * value back as `resume` to continue. `shouldStop` is checked between parts and, if true,
     * throws [UploadInterrupted] with progress already saved.
     */
    fun upload(
        id: String,
        file: File,
        resume: ResumeState? = null,
        onState: (ResumeState?) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ) {
        require(ID.matches(id)) { "bad id" }
        val size = file.length()
        if (size <= SINGLE_MAX) {
            try { putStream("/v1/objects/$id", file, 0, size, setOf(201)) } catch (e: HttpError) { if (e.code != 409) throw e }
            return
        }
        try {
            multipart(id, file, size, resume?.takeIf { validResume(it, size) }, onState, shouldStop)
        } catch (e: HttpError) {
            if (e.code == 409) { onState(null); return } // already uploaded earlier
            if (e.reason == "unknown_upload" && resume != null) {
                // The server forgot our upload (abandoned for a day). Start over once.
                onState(null)
                multipart(id, file, size, null, onState, shouldStop)
            } else throw e
        }
    }

    private fun validResume(r: ResumeState, size: Long): Boolean =
        r.uploadId.isNotEmpty() && r.parts.withIndex().all { (i, p) -> p.partNumber == i + 1 } &&
            r.parts.size * PART_SIZE <= size + PART_SIZE

    private fun multipart(id: String, file: File, size: Long, resume: ResumeState?, onState: (ResumeState?) -> Unit, shouldStop: () -> Boolean) {
        var state: ResumeState = resume ?: run {
            val c = open("POST", "/v1/objects/$id/multipart")
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            val start = "{\"size\":$size}".toByteArray() // the Worker reserves this much of the inbox cap
            c.setFixedLengthStreamingMode(start.size)
            c.outputStream.use { it.write(start) }
            val text = body(c, setOf(201))
            ResumeState((json.parseToJsonElement(text) as JsonObject)["uploadId"]!!.let { (it as JsonPrimitive).content })
                .also { onState(it) }
        }
        var n = state.parts.size + 1
        var off = (n - 1) * PART_SIZE
        var parts = state.parts
        while (off < size) {
            if (shouldStop()) throw UploadInterrupted()
            val len = minOf(PART_SIZE, size - off)
            val text = putStream("/v1/objects/$id/multipart/${state.uploadId}/$n", file, off, len, setOf(200))
            val p = json.parseToJsonElement(text) as JsonObject
            parts = parts + UploadedPart((p["partNumber"] as JsonPrimitive).content.toInt(), (p["etag"] as JsonPrimitive).content)
            state = ResumeState(state.uploadId, parts)
            onState(state)
            n++; off += len
        }
        val done = open("POST", "/v1/objects/$id/multipart/${state.uploadId}/complete")
        done.doOutput = true
        done.setRequestProperty("content-type", "application/json")
        val payload = buildJsonObject {
            put("parts", buildJsonArray { parts.forEach { add(buildJsonObject { put("partNumber", it.partNumber); put("etag", it.etag) }) } })
        }.toString().toByteArray()
        done.setFixedLengthStreamingMode(payload.size)
        done.outputStream.use { it.write(payload) }
        try { body(done, setOf(201)) } catch (e: HttpError) { if (e.code != 409) throw e }
        onState(null)
    }

    /** Current inbox limits and free space, without listing objects. */
    fun limits(): Limits = status(since = Long.MAX_VALUE / 2).limits

    fun status(since: Long = 0): StatusResponse {
        val c = open("GET", "/v1/status?since=$since")
        return json.decodeFromString(StatusResponse.serializer(), body(c, setOf(200)))
    }
}
