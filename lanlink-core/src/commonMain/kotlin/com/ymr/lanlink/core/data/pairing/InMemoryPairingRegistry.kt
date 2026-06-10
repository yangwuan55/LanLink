package com.ymr.lanlink.core.data.pairing

import com.ymr.lanlink.core.domain.pairing.PairingRecord
import com.ymr.lanlink.core.domain.pairing.PairingRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default in-memory [PairingRegistry]. Records live only for the process
 * lifetime; the host App injects a persistent implementation for durability.
 *
 * Access is guarded by a coroutine [Mutex] (not JVM `synchronized`) so it
 * compiles on every KMP target.
 */
class InMemoryPairingRegistry : PairingRegistry {
    private val records = mutableMapOf<String, PairingRecord>()
    private val mutex = Mutex()

    override suspend fun save(record: PairingRecord) {
        mutex.withLock { records[record.pairingId] = record }
    }

    override suspend fun find(pairingId: String): PairingRecord? =
        mutex.withLock { records[pairingId] }

    override suspend fun remove(pairingId: String) {
        mutex.withLock { records.remove(pairingId) }
    }

    override suspend fun all(): List<PairingRecord> =
        mutex.withLock { records.values.toList() }
}
