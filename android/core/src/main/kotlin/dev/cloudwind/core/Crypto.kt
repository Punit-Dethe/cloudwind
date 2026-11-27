package dev.cloudwind.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cloudwind file format, byte-compatible with the Rust `cloudwind-core` crate.
 *
 * ```
 * "CWND" | version u8 | chunk_size u32 BE | stream nonce (19 bytes)
 * STREAM-encrypted chunks (XChaCha20-Poly1305, BE32 counter, last-chunk flag)
 * plaintext = meta_len u32 BE | meta JSON | file bytes
 * ```
 *
 * XChaCha20-Poly1305 is built from HChaCha20 plus the platform's ChaCha20-Poly1305
 * (JDK 11+, Android 9+). Each chunk's 24-byte nonce is `nonce19 | counter u32 BE | last u8`.
 */
object Crypto {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'W'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
    private const val VERSION = 1
    private const val NONCE_LEN = 19
    private const val TAG_LEN = 16
    const val DEFAULT_CHUNK = 1 shl 20
    private const val MAX_CHUNK = 64 shl 20
    private const val MAX_META = 1 shl 20

    class CryptoException(message: String) : Exception(message)

    // ---- HChaCha20 -------------------------------------------------------

    private fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        require(key.size == 32 && nonce16.size == 16)
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(nonce16, i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        val words = intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15])
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (words[i] ushr (8 * j)).toByte()
        return out
    }

    // ---- XChaCha20-Poly1305 ----------------------------------------------

    private fun xchacha(mode: Int, key: ByteArray, nonce24: ByteArray, input: ByteArray, len: Int): ByteArray {
        val sub = hChaCha20(key, nonce24.copyOfRange(0, 16))
        val iv = ByteArray(12)
        System.arraycopy(nonce24, 16, iv, 4, 8)
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(mode, SecretKeySpec(sub, "ChaCha20"), IvParameterSpec(iv))
        return c.doFinal(input, 0, len)
    }

    private fun chunkNonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray {
        val n = ByteArray(24)
        System.arraycopy(prefix, 0, n, 0, NONCE_LEN)
        n[19] = (counter ushr 24).toByte(); n[20] = (counter ushr 16).toByte()
        n[21] = (counter ushr 8).toByte(); n[22] = counter.toByte()
        n[23] = if (last) 1 else 0
        return n
    }

    // ---- helpers ---------------------------------------------------------

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** SHA-256 of a stream as lowercase hex, plus its length. */
    fun hash(input: InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var n = 0L
        while (true) {
            val k = input.read(buf)
            if (k < 0) break
            md.update(buf, 0, k); n += k
        }
        return hex(md.digest()) to n
    }

    private fun readFull(input: InputStream, buf: ByteArray): Int {
        var t = 0
        while (t < buf.size) {
            val n = input.read(buf, t, buf.size - t)
            if (n < 0) break
            t += n
        }
        return t
    }

    // ---- encrypt ---------------------------------------------------------

    /**
     * Encrypt a file. `open` is called twice: once to hash, once to encrypt, so memory
     * stays at about two chunks no matter how big the video is.
     */
    fun encrypt(
        key: ByteArray,
        name: String,
        modified: Long?,
        open: () -> InputStream,
        out: OutputStream,
        chunk: Int = DEFAULT_CHUNK,
        random: java.security.SecureRandom = java.security.SecureRandom(),
    ): Meta {
        val (sha, size) = open().use { hash(it) }
        val meta = Meta(name, size, sha, modified)
        open().use { encryptWithMeta(key, meta, it, out, chunk, random) }
        return meta
    }

    fun encryptWithMeta(
        key: ByteArray,
        meta: Meta,
        src: InputStream,
        out: OutputStream,
        chunk: Int = DEFAULT_CHUNK,
        random: java.security.SecureRandom = java.security.SecureRandom(),
    ) {
        require(key.size == 32) { "key must be 32 bytes" }
        require(chunk in 1..MAX_CHUNK)
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        out.write(MAGIC)
        out.write(VERSION)
        out.write(be32(chunk))
        out.write(nonce)

        val metaJson = Meta.toJson(meta).toByteArray(Charsets.UTF_8)
        val head = ByteArrayOutputStream().apply { write(be32(metaJson.size)); write(metaJson) }.toByteArray()
        val input = java.io.SequenceInputStream(head.inputStream(), src)

        var buf = ByteArray(chunk)
        var cur = readFull(input, buf)
        var counter = 0
        while (true) {
            val next = ByteArray(chunk)
            val nlen = if (cur == chunk) readFull(input, next) else 0
            val last = nlen == 0
            out.write(xchacha(Cipher.ENCRYPT_MODE, key, chunkNonce(nonce, counter, last), buf, cur))
            if (last) break
            buf = next; cur = nlen; counter++
        }
        out.flush()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    // ---- decrypt ---------------------------------------------------------

    /** Decrypt to `out`, verifying the SHA-256 in the metadata. On failure discard `out`. */
    fun decrypt(key: ByteArray, src: InputStream, out: OutputStream): Meta {
        require(key.size == 32) { "key must be 32 bytes" }
        val hdr = ByteArray(4 + 1 + 4 + NONCE_LEN)
        if (readFull(src, hdr) != hdr.size || !hdr.copyOfRange(0, 4).contentEquals(MAGIC)) throw CryptoException("not a Cloudwind file")
        if (hdr[4].toInt() != VERSION) throw CryptoException("unsupported version ${hdr[4]}")
        val chunk = ((hdr[5].toInt() and 0xff) shl 24) or ((hdr[6].toInt() and 0xff) shl 16) or
            ((hdr[7].toInt() and 0xff) shl 8) or (hdr[8].toInt() and 0xff)
        if (chunk <= 0 || chunk > MAX_CHUNK) throw CryptoException("bad chunk size")
        val nonce = hdr.copyOfRange(9, 9 + NONCE_LEN)

        val ctLen = chunk + TAG_LEN
        var buf = ByteArray(ctLen)
        var cur = readFull(src, buf)
        if (cur == 0) throw CryptoException("empty body")
        val md = MessageDigest.getInstance("SHA-256")
        var head = ByteArrayOutputStream()
        var meta: Meta? = null
        var written = 0L
        var counter = 0

        fun route(plain: ByteArray) {
            var data = plain
            if (meta == null) {
                head.write(data)
                data = ByteArray(0)
                val h = head.toByteArray()
                if (h.size >= 4) {
                    val l = ((h[0].toInt() and 0xff) shl 24) or ((h[1].toInt() and 0xff) shl 16) or
                        ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
                    if (l < 0 || l > MAX_META) throw CryptoException("bad metadata length")
                    if (h.size >= 4 + l) {
                        meta = Meta.fromJson(String(h, 4, l, Charsets.UTF_8))
                        data = h.copyOfRange(4 + l, h.size)
                        head = ByteArrayOutputStream()
                    }
                }
            }
            if (meta != null && data.isNotEmpty()) {
                md.update(data); written += data.size; out.write(data)
            }
        }

        while (true) {
            val next = ByteArray(ctLen)
            val nlen = if (cur == ctLen) readFull(src, next) else 0
            val last = nlen == 0
            val plain = try {
                xchacha(Cipher.DECRYPT_MODE, key, chunkNonce(nonce, counter, last), buf, cur)
            } catch (e: GeneralSecurityException) {
                throw CryptoException("decryption failed (wrong key or corrupted data)")
            }
            route(plain)
            if (last) break
            buf = next; cur = nlen; counter++
        }
        out.flush()
        val m = meta ?: throw CryptoException("missing metadata")
        if (written != m.size || hex(md.digest()) != m.sha256) throw CryptoException("hash mismatch")
        return m
    }
}
