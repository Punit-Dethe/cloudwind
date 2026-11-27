package dev.cloudwind.core

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the PC shows as a QR code: `cloudwind://pair/<base64url(json)>`. */
data class Pairing(
    val key: ByteArray,
    val url: String,
    val token: String,
    /** SHA-256 of the PC's LAN certificate and its port; null if the PC didn't advertise direct sync. */
    val lanFingerprint: String? = null,
    val lanPort: Int? = null,
) {
    override fun equals(other: Any?) =
        other is Pairing && key.contentEquals(other.key) && url == other.url && token == other.token &&
            lanFingerprint == other.lanFingerprint && lanPort == other.lanPort
    override fun hashCode() = key.contentHashCode() xor url.hashCode() xor token.hashCode()
    override fun toString() = "Pairing(url=$url)" // never print the key or token

    @Serializable
    private data class Payload(
        val v: Int = 1, val key: String, val url: String? = null, val token: String? = null,
        val fp: String? = null, val port: Int? = null,
    )

    companion object {
        private const val PREFIX = "cloudwind://pair/"

        /** Returns null if `text` isn't a usable pairing code. */
        fun parse(text: String): Pairing? = try {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) null else {
                val raw = String(Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)), Charsets.UTF_8)
                val p = Json { ignoreUnknownKeys = true }.decodeFromString(Payload.serializer(), raw)
                val key = Base64.getUrlDecoder().decode(p.key)
                val url = p.url?.trim()?.trimEnd('/')
                if (p.v != 1 || key.size != 32 || url.isNullOrEmpty() || p.token.isNullOrEmpty() ||
                    !(url.startsWith("https://") || url.startsWith("http://"))
                ) null else Pairing(
                    key, url, p.token,
                    lanFingerprint = p.fp?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) },
                    lanPort = p.port?.takeIf { it in 1..65535 },
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
