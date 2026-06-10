package com.ymr.lanlink.core.domain.pairing

/**
 * One server-side pairing record: the per-client shared [secret] minted during
 * the first PIN handshake, plus metadata for App-side management.
 *
 * NOTE: contains a [ByteArray], so generated equals/hashCode fall back to
 * reference equality. Compare [secret] by content where it matters.
 */
data class PairingRecord(
    val pairingId: String,
    val secret: ByteArray,
    val clientDeviceName: String,
    val pairedAt: Long,
    val lastSeenAt: Long,
)

/**
 * Server-side store of pairing records, keyed by `pairingId` (1 : N — one record
 * per paired client, each with its own [PairingRecord.secret]).
 *
 * lanlink-core ships only [com.ymr.lanlink.core.data.pairing.InMemoryPairingRegistry];
 * the host App injects a persistent implementation (DataStore, etc.) to survive
 * process restarts. The secret is sensitive — persist it encrypted.
 */
interface PairingRegistry {
    suspend fun save(record: PairingRecord)
    suspend fun find(pairingId: String): PairingRecord?
    suspend fun remove(pairingId: String)
    suspend fun all(): List<PairingRecord>
}
