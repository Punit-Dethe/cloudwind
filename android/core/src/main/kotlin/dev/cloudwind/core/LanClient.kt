package dev.cloudwind.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `Authorization` token for the LAN link. Derived from the library key, so only paired phones can compute it. */
fun lanAuthToken(key: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return Crypto.hex(mac.doFinal("cloudwind/lan-auth/v1".toByteArray()))
}

@Serializable data class LanPlanItem(val id: String, val sha256: String, val size: Long, val object_id: String? = null)
@Serializable data class LanWant(val id: String, val offset: Long = 0)
@Serializable data class LanPlan(val have: List<String> = emptyList(), val cloud: List<String> = emptyList(), val want: List<LanWant> = emptyList())
@Serializable private data class LanPlanRequest(val items: List<LanPlanItem>)
@Serializable private data class HaveBody(val have: Long = 0)

sealed interface LanSend {
    /** Verified and stored on the PC. `duplicate` means it already had these bytes. */
    data class Stored(val duplicate: Boolean) : LanSend
    data class Progress(val have: Long) : LanSend
    data class Conflict(val have: Long) : LanSend
    data object BadHash : LanSend
    data object Busy : LanSend
}

/** The PC can't be reached or authenticated on this network. */
class LanUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to the PC over HTTPS, trusting exactly one certificate: the one whose SHA-256 fingerprint was in the
 * pairing QR. No certificate authority is involved, so a look-alike PC on the same Wi-Fi is rejected.
 */
class LanClient(
    private val host: String,
    private val port: Int,
    pinSha256Hex: String,
    private val token: String,
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 30_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val factory: SSLSocketFactory = pinnedFactory(pinSha256Hex.lowercase())

    private fun open(method: String, path: String): HttpsURLConnection {
        val c = java.net.URL("https://$host:$port$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = factory
        c.setHostnameVerifier { _, _ -> true } // we connect by IP and pin the certificate instead
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("authorization", "Bearer $token")
        return c
    }

    /** True if the PC at this address presents the pinned certificate and accepts our token. */
    fun hello(): Boolean = try {
        open("GET", "/lan/v1/hello").responseCode == 200
    } catch (e: IOException) {
        false
    }

    fun plan(items: List<LanPlanItem>): LanPlan {
        val body = json.encodeToString(LanPlanRequest.serializer(), LanPlanRequest(items)).toByteArray()
        val c = open("POST", "/lan/v1/plan")
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw LanUnavailable("PC refused the plan: HTTP ${c.responseCode}")
            return json.decodeFromString(LanPlan.serializer(), c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: javax.net.ssl.SSLException) {
            throw LanUnavailable("not the paired PC", e)
        }
    }

    /**
     * Send `size` bytes of the file starting at `offset`. `open` yields a fresh stream of the whole file
     * (we skip to `offset`). `shouldStop` is checked every chunk; stopping leaves the PC with a resumable partial.
     */
    fun send(
        sha256: String, size: Long, offset: Long, name: String, modified: Long?, objectId: String?,
        open: () -> InputStream, shouldStop: () -> Boolean = { false },
    ): LanSend {
        val q = buildString {
            append("?size=$size&offset=$offset&name=${URLEncoder.encode(name, "UTF-8")}")
            if (modified != null) append("&modified=$modified")
            if (objectId != null) append("&object=${URLEncoder.encode(objectId, "UTF-8")}")
        }
        val c = open("PUT", "/lan/v1/file/$sha256$q")
        c.doOutput = true
        c.setFixedLengthStreamingMode(size - offset)
        try {
            open().use { input ->
                var skipped = 0L
                while (skipped < offset) { val n = input.skip(offset - skipped); if (n <= 0) throw IOException("file shorter than expected"); skipped += n }
                c.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    var left = size - offset
                    while (left > 0) {
                        if (shouldStop()) { c.disconnect(); throw UploadInterrupted() }
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("file shorter than expected")
                        out.write(buf, 0, n); left -= n
                    }
                }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            fun have() = try { json.decodeFromString(HaveBody.serializer(), text).have } catch (e: Exception) { 0L }
            return when (code) {
                200 -> LanSend.Stored(duplicate = text.contains("\"stored\":false"))
                202 -> LanSend.Progress(have())
                409 -> LanSend.Conflict(have())
                422 -> LanSend.BadHash
                423 -> LanSend.Busy
                401, 403 -> throw LanUnavailable("PC rejected this phone")
                else -> throw IOException("PC replied HTTP $code")
            }
        } catch (e: javax.net.ssl.SSLException) {
            throw LanUnavailable("not the paired PC", e)
        }
    }

    private fun pinnedFactory(pin: String): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no client auth")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = Crypto.hex(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))
                if (!MessageDigest.isEqual(got.toByteArray(), pin.toByteArray())) throw CertificateException("certificate does not match the paired PC")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return ctx.socketFactory
    }
}

/** The PC's address announcement, encrypted with the library key so the cloud learns nothing from it. */
@Serializable
data class LanHint(val addrs: List<String> = emptyList(), val port: Int = 0, val at: Long = 0) {
    companion object {
        fun decode(payload: String, key: ByteArray): LanHint? = try {
            val out = java.io.ByteArrayOutputStream()
            Crypto.decrypt(key, Base64.getUrlDecoder().decode(payload).inputStream(), out)
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), out.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }
}

/** Find the PC: try the last address that worked, then the announced ones. First pinned handshake wins. */
class LanFinder(private val pin: String, private val token: String, private val port: Int) {
    fun find(candidates: List<String>, hintPort: Int? = null): LanClient? {
        for (host in candidates.distinct()) {
            val c = LanClient(host, hintPort ?: port, pin, token)
            if (c.hello()) return c
        }
        return null
    }
}
