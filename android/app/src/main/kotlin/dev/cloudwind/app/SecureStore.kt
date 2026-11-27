package dev.cloudwind.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.cloudwind.core.Pairing
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the library key and Worker token encrypted with a hardware-backed Android
 * Keystore key. The raw key never touches disk unencrypted and is never logged.
 */
class SecureStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cloudwind_secure", Context.MODE_PRIVATE)

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun put(name: String, bytes: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val blob = c.iv + c.doFinal(bytes)
        sp.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): ByteArray? {
        val blob = sp.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
            c.doFinal(blob, 12, blob.size - 12)
        } catch (e: Exception) {
            null // wrapping key lost (e.g. restored to a new phone): treat as unpaired
        }
    }

    fun savePairing(p: Pairing) {
        put("key", p.key)
        put("url", p.url.toByteArray())
        put("token", p.token.toByteArray())
    }

    fun loadPairing(): Pairing? {
        val key = get("key") ?: return null
        val url = get("url")?.toString(Charsets.UTF_8) ?: return null
        val token = get("token")?.toString(Charsets.UTF_8) ?: return null
        return Pairing(key, url, token)
    }

    fun clear() = sp.edit().clear().apply()

    private companion object { const val ALIAS = "cloudwind_wrap_v1" }
}
