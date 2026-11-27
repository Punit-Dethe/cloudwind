package dev.cloudwind.core

import java.security.MessageDigest

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439), pure Kotlin, empty AAD.
 *
 * Written in-house so the app doesn't depend on which JCE provider a given Android
 * build ships. Verified against the JDK implementation and the RFC vectors in tests.
 */
internal object ChaChaPoly {
    private fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    /** One 64-byte keystream block. */
    private fun block(key: IntArray, counter: Int, nonce: IntArray, out: ByteArray, off: Int) {
        val init = IntArray(16)
        init[0] = 0x61707865; init[1] = 0x3320646e; init[2] = 0x79622d32; init[3] = 0x6b206574
        System.arraycopy(key, 0, init, 4, 8)
        init[12] = counter
        init[13] = nonce[0]; init[14] = nonce[1]; init[15] = nonce[2]
        val s = init.copyOf()
        repeat(10) {
            qr(s, 0, 4, 8, 12); qr(s, 1, 5, 9, 13); qr(s, 2, 6, 10, 14); qr(s, 3, 7, 11, 15)
            qr(s, 0, 5, 10, 15); qr(s, 1, 6, 11, 12); qr(s, 2, 7, 8, 13); qr(s, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val v = s[i] + init[i]
            out[off + i * 4] = v.toByte(); out[off + i * 4 + 1] = (v ushr 8).toByte()
            out[off + i * 4 + 2] = (v ushr 16).toByte(); out[off + i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    private fun words(b: ByteArray, count: Int) = IntArray(count) { le32(b, it * 4) }

    private fun xorStream(key: ByteArray, nonce: ByteArray, startCounter: Int, data: ByteArray, len: Int): ByteArray {
        val k = words(key, 8); val n = words(nonce, 3)
        val out = ByteArray(len)
        val ks = ByteArray(64)
        var counter = startCounter
        var i = 0
        while (i < len) {
            block(k, counter++, n, ks, 0)
            val m = minOf(64, len - i)
            for (j in 0 until m) out[i + j] = (data[i + j].toInt() xor ks[j].toInt()).toByte()
            i += m
        }
        return out
    }

    // ---- Poly1305 (26-bit limbs) ----------------------------------------

    private fun poly1305(key: ByteArray, ct: ByteArray, ctLen: Int): ByteArray {
        fun u32(b: ByteArray, o: Int) = le32(b, o).toLong() and 0xffffffffL
        val m26 = 0x3ffffffL
        val r0 = u32(key, 0) and 0x3ffffffL
        val r1 = (u32(key, 3) ushr 2) and 0x3ffff03L
        val r2 = (u32(key, 6) ushr 4) and 0x3ffc0ffL
        val r3 = (u32(key, 9) ushr 6) and 0x3f03fffL
        val r4 = (u32(key, 12) ushr 8) and 0x00fffffL
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L

        // Message = ct | pad16 | aadLen(0) u64 | ctLen u64. Built lazily by 16-byte block.
        val padded = (ctLen + 15) / 16 * 16
        val total = padded + 16
        val blk = ByteArray(16)
        var pos = 0
        while (pos < total) {
            java.util.Arrays.fill(blk, 0)
            if (pos < padded) {
                val n = minOf(16, ctLen - pos).coerceAtLeast(0)
                if (n > 0) System.arraycopy(ct, pos, blk, 0, n)
            } else {
                // lengths block: aad len = 0 (8 bytes), ct len (8 bytes LE)
                var v = ctLen.toLong()
                for (i in 0 until 8) { blk[8 + i] = v.toByte(); v = v ushr 8 }
            }
            val t0 = u32(blk, 0); val t1 = u32(blk, 4); val t2 = u32(blk, 8); val t3 = u32(blk, 12)
            h0 += t0 and m26
            h1 += ((t0 ushr 26) or (t1 shl 6)) and m26
            h2 += ((t1 ushr 20) or (t2 shl 12)) and m26
            h3 += ((t2 ushr 14) or (t3 shl 18)) and m26
            h4 += (t3 ushr 8) or (1L shl 24)

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and m26
            d1 += c; c = d1 ushr 26; h1 = d1 and m26
            d2 += c; c = d2 ushr 26; h2 = d2 and m26
            d3 += c; c = d3 ushr 26; h3 = d3 and m26
            d4 += c; c = d4 ushr 26; h4 = d4 and m26
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and m26
            h1 += c
            pos += 16
        }

        var c = h1 ushr 26; h1 = h1 and m26
        h2 += c; c = h2 ushr 26; h2 = h2 and m26
        h3 += c; c = h3 ushr 26; h3 = h3 and m26
        h4 += c; c = h4 ushr 26; h4 = h4 and m26
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and m26
        h1 += c

        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and m26
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and m26
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and m26
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and m26
        val g4 = h4 + c - (1L shl 26)
        val mask = (g4 ushr 63) - 1 // all ones if h >= p (g4 >= 0)
        val nmask = mask.inv()
        h0 = (h0 and nmask) or (g0 and mask)
        h1 = (h1 and nmask) or (g1 and mask)
        h2 = (h2 and nmask) or (g2 and mask)
        h3 = (h3 and nmask) or (g3 and mask)
        h4 = (h4 and nmask) or (g4 and mask)

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var f = w0 + u32(key, 16)
        val out = ByteArray(16)
        fun put(o: Int, v: Long) { for (i in 0 until 4) out[o + i] = (v ushr (8 * i)).toByte() }
        put(0, f and 0xffffffffL); f = w1 + u32(key, 20) + (f ushr 32)
        put(4, f and 0xffffffffL); f = w2 + u32(key, 24) + (f ushr 32)
        put(8, f and 0xffffffffL); f = w3 + u32(key, 28) + (f ushr 32)
        put(12, f and 0xffffffffL)
        return out
    }

    // ---- AEAD ------------------------------------------------------------

    private fun polyKey(key: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArray(64)
        block(words(key, 8), 0, words(nonce, 3), out, 0)
        return out.copyOf(32)
    }

    /** Returns ciphertext || 16-byte tag. */
    fun seal(key: ByteArray, nonce12: ByteArray, plain: ByteArray, len: Int): ByteArray {
        require(key.size == 32 && nonce12.size == 12)
        val ct = xorStream(key, nonce12, 1, plain, len)
        val tag = poly1305(polyKey(key, nonce12), ct, len)
        return ct + tag
    }

    /** Throws [Crypto.CryptoException] if the tag doesn't verify. */
    fun open(key: ByteArray, nonce12: ByteArray, boxed: ByteArray, len: Int): ByteArray {
        require(key.size == 32 && nonce12.size == 12)
        if (len < 16) throw Crypto.CryptoException("decryption failed (wrong key or corrupted data)")
        val ctLen = len - 16
        val ct = boxed.copyOf(ctLen)
        val want = poly1305(polyKey(key, nonce12), ct, ctLen)
        if (!MessageDigest.isEqual(want, boxed.copyOfRange(ctLen, len))) {
            throw Crypto.CryptoException("decryption failed (wrong key or corrupted data)")
        }
        return xorStream(key, nonce12, 1, ct, ctLen)
    }
}
