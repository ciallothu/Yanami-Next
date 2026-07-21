package com.sekusarisu.yanami.data.local.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BiometricEnvelopeCodecTest {

    @Test
    fun `round trips a bounded v1 envelope`() {
        val keyId = "AAAAAAAAAAAAAAAAAAAAAA"
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(48) { (it * 3).toByte() }

        val decoded =
                BiometricEnvelopeCodec.decode(
                        BiometricEnvelopeCodec.encode(keyId, iv, ciphertext)
                )!!

        org.junit.Assert.assertEquals(keyId, decoded.keyId)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun `rejects unknown malformed and oversized envelopes`() {
        assertNull(BiometricEnvelopeCodec.decode("v2.AAAAAAAAAAAAAAAAAAAAAA.AAAA.AAAA"))
        assertNull(BiometricEnvelopeCodec.decode("v1.not-an-id.AAAA.AAAA"))
        assertNull(BiometricEnvelopeCodec.decode("v1.AAAAAAAAAAAAAAAAAAAAAA.AAAA.AAAA"))
        assertNull(
                BiometricEnvelopeCodec.decode(
                        "v1.AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAA=.AAAAAAAAAAAAAAAAAAAAAAA"
                )
        )
        assertNull(BiometricEnvelopeCodec.decode("x".repeat(513)))
    }

    @Test
    fun `refuses invalid material before persistence`() {
        assertThrows(IllegalArgumentException::class.java) {
            BiometricEnvelopeCodec.encode("not-an-id", ByteArray(12), ByteArray(48))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BiometricEnvelopeCodec.encode(
                    "AAAAAAAAAAAAAAAAAAAAAA",
                    ByteArray(8),
                    ByteArray(48)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BiometricEnvelopeCodec.encode(
                    "AAAAAAAAAAAAAAAAAAAAAA",
                    ByteArray(12),
                    ByteArray(16)
            )
        }
    }
}
