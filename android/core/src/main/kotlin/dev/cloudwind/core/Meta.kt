package dev.cloudwind.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Metadata stored (encrypted) inside every file; matches the Rust `Meta` struct. */
@Serializable
data class Meta(
    val name: String,
    val size: Long,
    val sha256: String,
    val modified: Long? = null,
    val folder: String? = null,
) {
    companion object {
        // explicitNulls = true so `modified: null` is written, exactly like serde does.
        private val json = Json { explicitNulls = true; encodeDefaults = true; ignoreUnknownKeys = true }
        fun toJson(m: Meta): String = json.encodeToString(serializer(), m)
        fun fromJson(s: String): Meta = try {
            json.decodeFromString(serializer(), s)
        } catch (e: Exception) {
            throw Crypto.CryptoException("bad metadata")
        }
    }
}
