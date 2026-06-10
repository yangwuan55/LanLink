package com.ymr.lanlink.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 4231 HMAC-SHA256 test vectors plus SHA-256 sanity vectors, pinning the
 * pure-Kotlin [Sha256]/[Hmac] implementations. Any drift fails here.
 */
class HmacSha256Test {

    private fun hex(s: String): ByteArray {
        val clean = s.removePrefix("0x")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun toHex(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha256_emptyAndAbc() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            toHex(Sha256.digest(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            toHex(Sha256.digest("abc".encodeToByteArray())),
        )
    }

    // RFC 4231 Section 4.2
    @Test
    fun rfc4231_case1() {
        val key = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val data = "Hi There".encodeToByteArray()
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            toHex(Hmac.sha256(key, data)),
        )
    }

    // RFC 4231 Section 4.3
    @Test
    fun rfc4231_case2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            toHex(Hmac.sha256(key, data)),
        )
    }

    // RFC 4231 Section 4.4
    @Test
    fun rfc4231_case3() {
        val key = hex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val data = hex("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
        assertEquals(
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            toHex(Hmac.sha256(key, data)),
        )
    }

    // RFC 4231 Section 4.5
    @Test
    fun rfc4231_case4() {
        val key = hex("0102030405060708090a0b0c0d0e0f10111213141516171819")
        val data = hex("cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd")
        assertEquals(
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            toHex(Hmac.sha256(key, data)),
        )
    }

    // RFC 4231 Section 4.6 (truncated to 128 bits) — verify the prefix matches.
    @Test
    fun rfc4231_case5_truncated() {
        val key = hex("0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c")
        val data = "Test With Truncation".encodeToByteArray()
        val full = Hmac.sha256(key, data)
        assertEquals("a3b6167473100ee06e0c796c2955552b", toHex(full.copyOf(16)))
    }

    // RFC 4231 Section 4.7 — key longer than the block size (131 bytes).
    @Test
    fun rfc4231_case6_longKey() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            toHex(Hmac.sha256(key, data)),
        )
    }

    // RFC 4231 Section 4.8 — long key AND long data.
    @Test
    fun rfc4231_case7_longKeyLongData() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = ("This is a test using a larger than block-size key and a larger " +
            "than block-size data. The key needs to be hashed before being used " +
            "by the HMAC algorithm.").encodeToByteArray()
        assertEquals(
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            toHex(Hmac.sha256(key, data)),
        )
    }

    @Test
    fun constantTimeEquals_behaves_like_contentEquals() {
        val a = byteArrayOf(1, 2, 3, 4)
        assertTrue(constantTimeEquals(a, byteArrayOf(1, 2, 3, 4)))
        assertFalse(constantTimeEquals(a, byteArrayOf(1, 2, 3, 5)))
        assertFalse(constantTimeEquals(a, byteArrayOf(1, 2, 3)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun tokenProofs_domainSeparation_serverProof_differs_from_clientProof() {
        val secret = ByteArray(32) { it.toByte() }
        val n1 = ByteArray(16) { 7 }
        val n2 = ByteArray(16) { 9 }
        val srv = TokenProofs.serverProof(secret, n1, n2)
        val cli = TokenProofs.clientProof(secret, n2, n1)
        assertFalse(constantTimeEquals(srv, cli), "srv/cli tags must separate the two proofs")
        // Deterministic for the same inputs.
        assertContentEquals(srv, TokenProofs.serverProof(secret, n1, n2))
    }
}
