package dev.cloudwind.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChaChaPolyTest {
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun empty_message_matches_jdk() {
        val key = ByteArray(32); val nonce = ByteArray(12)
        val sealed = ChaChaPoly.seal(key, nonce, ByteArray(0), 0)
        val jdk = jdk(Cipher.ENCRYPT_MODE, key, nonce, ByteArray(0), 0)
        assertContentEquals(jdk, sealed)
    }

    private fun jdk(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return c.doFinal(data, 0, len)
    }

    @Test
    fun matches_jdk_for_many_lengths_and_random_keys() {
        val rnd = SecureRandom()
        for (len in (0..300) + listOf(1000, 4096, 65536, 100_003)) {
            val key = ByteArray(32).also { rnd.nextBytes(it) }
            val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val data = ByteArray(len).also { rnd.nextBytes(it) }
            val mine = ChaChaPoly.seal(key, nonce, data, len)
            assertContentEquals(jdk(Cipher.ENCRYPT_MODE, key, nonce, data, len), mine, "seal len=$len")
            assertContentEquals(data, ChaChaPoly.open(key, nonce, mine, mine.size), "open len=$len")
        }
    }

    @Test
    fun rejects_bad_tag_and_short_input() {
        val key = ByteArray(32) { 1 }; val nonce = ByteArray(12) { 2 }
        val sealed = ChaChaPoly.seal(key, nonce, ByteArray(50) { 3 }, 50)
        sealed[10] = (sealed[10].toInt() xor 1).toByte()
        assertFailsWith<Crypto.CryptoException> { ChaChaPoly.open(key, nonce, sealed, sealed.size) }
        assertFailsWith<Crypto.CryptoException> { ChaChaPoly.open(key, nonce, ByteArray(5), 5) }
    }

    @Test
    fun poly_edge_case_all_ff_message() {
        // Exercises the final reduction (h >= p) path.
        val key = ByteArray(32) { 0xff.toByte() }; val nonce = ByteArray(12) { 0xff.toByte() }
        for (len in listOf(16, 17, 32, 48, 64)) {
            val d = ByteArray(len) { 0xff.toByte() }
            assertContentEquals(jdk(Cipher.ENCRYPT_MODE, key, nonce, d, len), ChaChaPoly.seal(key, nonce, d, len))
        }
        assertEquals(true, true)
    }
}
