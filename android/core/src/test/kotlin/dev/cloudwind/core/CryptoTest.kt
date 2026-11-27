package dev.cloudwind.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CryptoTest {
    // Same key as the Rust fixtures: bytes 0..31.
    private val key = ByteArray(32) { it.toByte() }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hChaCha20_matches_published_vector() {
        // draft-irtf-cfrg-xchacha-03, section 2.2.1
        val nonce = unhex("000000090000004a0000000031415927")
        val want = "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc"
        assertEquals(want, Crypto.hex(Crypto.hChaCha20(key, nonce)))
    }

    private fun roundtrip(data: ByteArray, chunk: Int): ByteArray {
        val ct = ByteArrayOutputStream()
        val meta = Crypto.encrypt(key, "a.jpg", 1L, { ByteArrayInputStream(data) }, ct, chunk)
        assertEquals(data.size.toLong(), meta.size)
        val out = ByteArrayOutputStream()
        val back = Crypto.decrypt(key, ByteArrayInputStream(ct.toByteArray()), out)
        assertEquals(meta, back)
        return out.toByteArray()
    }

    @Test
    fun roundtrips_many_sizes() {
        for (chunk in listOf(16, 64, 1000)) for (len in listOf(0, 1, 15, 16, 17, 100, 1000, 1001, 5000, 192)) {
            val data = ByteArray(len) { (it * 31 % 251).toByte() }
            assertContentEquals(data, roundtrip(data, chunk), "chunk=$chunk len=$len")
        }
    }

    @Test
    fun decrypts_file_encrypted_by_rust() {
        val ct = javaClass.getResourceAsStream("/rust.cwnd")!!.readBytes()
        val out = ByteArrayOutputStream()
        val meta = Crypto.decrypt(key, ByteArrayInputStream(ct), out)
        assertEquals("fixture ü.jpg", meta.name)
        assertEquals(1_700_000_000L, meta.modified)
        assertContentEquals(ByteArray(1000) { (it * 31 % 251).toByte() }, out.toByteArray())
    }

    /** Writes the file the Rust test `decrypts_file_encrypted_by_kotlin` reads. */
    @Test
    fun writes_fixture_for_rust() {
        val data = ByteArray(3000) { (it * 7 % 253).toByte() }
        val ct = ByteArrayOutputStream()
        Crypto.encrypt(key, "kotlin ü.jpg", null, { ByteArrayInputStream(data) }, ct, 100)
        val dest = File(System.getProperty("fixtureDir") ?: "build/fixtures").also { it.mkdirs() }
        File(dest, "kotlin.cwnd").writeBytes(ct.toByteArray())
    }

    @Test
    fun wrong_key_tamper_and_truncation_fail() {
        val data = ByteArray(500) { 7 }
        val ct = ByteArrayOutputStream()
        Crypto.encrypt(key, "x", null, { ByteArrayInputStream(data) }, ct, 64)
        val bytes = ct.toByteArray()

        assertFailsWith<Crypto.CryptoException> {
            Crypto.decrypt(ByteArray(32) { 9 }, ByteArrayInputStream(bytes), ByteArrayOutputStream())
        }
        val bad = bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<Crypto.CryptoException> { Crypto.decrypt(key, ByteArrayInputStream(bad), ByteArrayOutputStream()) }
        val cut = bytes.copyOf(bytes.size - (64 + 16))
        assertFailsWith<Crypto.CryptoException> { Crypto.decrypt(key, ByteArrayInputStream(cut), ByteArrayOutputStream()) }
    }

    @Test
    fun file_name_is_not_visible_in_ciphertext() {
        val ct = ByteArrayOutputStream()
        Crypto.encrypt(key, "secret-name.jpg", null, { ByteArrayInputStream(byteArrayOf(1)) }, ct, 64)
        assertFalse(String(ct.toByteArray(), Charsets.ISO_8859_1).contains("secret"))
    }
}

class PairingTest {
    private fun code(json: String) = "cloudwind://pair/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    private val goodKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun parses_agent_output() {
        val p = Pairing.parse(code("""{"v":1,"key":"$goodKey","url":"https://cw.example.workers.dev/","token":"abc"}"""))!!
        assertEquals("https://cw.example.workers.dev", p.url)
        assertEquals("abc", p.token)
        assertContentEquals(ByteArray(32) { it.toByte() }, p.key)
        assertFalse(p.toString().contains("abc"))
    }

    @Test
    fun rejects_garbage() {
        assertEquals(null, Pairing.parse("hello"))
        assertEquals(null, Pairing.parse(code("""{"v":1,"key":"AAAA","url":"https://x","token":"t"}""")))
        assertEquals(null, Pairing.parse(code("""{"v":1,"key":"$goodKey","url":null,"token":"t"}""")))
        assertEquals(null, Pairing.parse(code("""{"v":1,"key":"$goodKey","url":"ftp://x","token":"t"}""")))
        assertEquals(null, Pairing.parse(code("""{"v":2,"key":"$goodKey","url":"https://x","token":"t"}""")))
    }
}
