package com.ymr.lanlink.core.domain.pairing

/**
 * Client-side store for the single most-recent pairing credential blob
 * (`localData`, 1 : 1 — one server per store). Backs the no-arg
 * [com.ymr.lanlink.core.service.PinConnectionService.reconnectLastServer]:
 * a successful PIN pairing (or address refresh) auto-[save]s the blob, a
 * `PAIRING_REVOKED` verdict auto-[clear]s it, and reconnect reads it via [load].
 *
 * lanlink-core ships only [com.ymr.lanlink.core.data.pairing.InMemoryPairingCredentialStore];
 * the host App injects a persistent implementation (SharedPreferences, DataStore,
 * etc.) to survive process restarts. The blob is sensitive — persist it encrypted.
 */
interface PairingCredentialStore {
    /** Persists [localData], replacing any previously stored blob. */
    suspend fun save(localData: String)

    /** Returns the stored blob, or null if none has been saved. */
    suspend fun load(): String?

    /** Removes the stored blob (e.g. after a revoked pairing). */
    suspend fun clear()
}
