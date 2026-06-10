package com.ymr.lanlink.core.data.pairing

import com.ymr.lanlink.core.domain.pairing.PairingCredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default in-memory [PairingCredentialStore]. The blob lives only for the
 * process lifetime; the host App injects a persistent implementation for
 * durability across restarts.
 *
 * Access is guarded by a coroutine [Mutex] (not JVM `synchronized`) so it
 * compiles on every KMP target.
 */
class InMemoryPairingCredentialStore : PairingCredentialStore {
    private var blob: String? = null
    private val mutex = Mutex()

    override suspend fun save(localData: String) {
        mutex.withLock { blob = localData }
    }

    override suspend fun load(): String? =
        mutex.withLock { blob }

    override suspend fun clear() {
        mutex.withLock { blob = null }
    }
}
