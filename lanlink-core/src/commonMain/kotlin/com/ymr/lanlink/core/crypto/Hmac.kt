package com.ymr.lanlink.core.crypto

/**
 * HMAC-SHA256 (RFC 2104) over the pure-Kotlin [Sha256], plus a constant-time
 * comparator for verifying MAC proofs.
 *
 * commonMain-safe — no platform crypto. Verified against the RFC 4231 test
 * vectors (see commonTest). Proof comparison MUST go through
 * [constantTimeEquals] so verification time does not leak how many leading
 * bytes matched.
 */
object Hmac {

    private const val IPAD = 0x36.toByte()
    private const val OPAD = 0x5c.toByte()

    /** Computes HMAC-SHA256 of [message] under [key]. Returns the 32-byte MAC. */
    fun sha256(key: ByteArray, message: ByteArray): ByteArray {
        // Keys longer than the block size are first hashed down.
        val block = ByteArray(Sha256.BLOCK_SIZE)
        val normalizedKey = if (key.size > Sha256.BLOCK_SIZE) Sha256.digest(key) else key
        normalizedKey.copyInto(block)

        val inner = ByteArray(Sha256.BLOCK_SIZE)
        val outer = ByteArray(Sha256.BLOCK_SIZE)
        for (i in block.indices) {
            inner[i] = (block[i].toInt() xor IPAD.toInt()).toByte()
            outer[i] = (block[i].toInt() xor OPAD.toInt()).toByte()
        }

        val innerHash = Sha256.digest(inner + message)
        return Sha256.digest(outer + innerHash)
    }
}

/**
 * Constant-time byte-array equality. Compares every byte regardless of where a
 * mismatch occurs, so timing does not reveal how much of [a] matched [b].
 * Returns false immediately for length mismatch (length is not secret).
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].toInt() xor b[i].toInt())
    }
    return diff == 0
}
