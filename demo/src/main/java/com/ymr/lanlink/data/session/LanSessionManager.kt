package com.ymr.lanlink.data.session

import android.content.Context
import android.os.Build
import android.util.Log
import com.ymr.lanlink.core.data.pairing.DataStorePairingCredentialStore
import com.ymr.lanlink.core.data.pairing.DataStorePairingRegistry
import com.ymr.lanlink.data.pairing.pairingDataStore
import com.ymr.lanlink.data.repository.LanRepository
import com.ymr.lanlink.core.net.android.AndroidLanNetworkFactory
import com.ymr.lanlink.core.service.PinConnectionServiceImpl
import java.util.UUID

/**
 * Process-level holder for the single [LanRepository] (and the one
 * [PinConnectionServiceImpl] it wraps).
 *
 * Why this exists: the LanLink server (TCP listener + UDP advertiser) lives
 * inside the repository's CoroutineScope, and a START_STICKY foreground service
 * keeps the process alive across Activity recreation. If each Activity-scoped
 * ViewModel constructed its own repository, the old one would become an orphan —
 * no one holds a reference to disconnect it, yet its scope keeps the old TCP port
 * bound and keeps advertising. The new ViewModel then binds a second port, so the
 * device advertises two ports at once and clients reconnect to the stale one.
 *
 * Guarantee: at most one [LanRepository] exists in the process at any time, so at
 * most one TcpSocketServer + one advertiser. Activity recreation reuses the same
 * instance; only an explicit [close] (stop-service path) tears it down.
 */
object LanSessionManager {

    private const val TAG = "LanSessionManager"

    private val lock = Any()

    private var repository: LanRepository? = null
    private var credentialStoreInstance: DataStorePairingCredentialStore? = null

    /** Returns the singleton repository, lazily constructing it on first use. */
    fun get(context: Context): LanRepository {
        synchronized(lock) {
            repository?.let {
                Log.i(TAG, "get: reusing existing repository@${System.identityHashCode(it).toString(16)}")
                return it
            }
            val appContext = context.applicationContext
            val dataStore = appContext.pairingDataStore
            val service = PinConnectionServiceImpl(
                AndroidLanNetworkFactory(),
                pairingRegistry = DataStorePairingRegistry(dataStore),
                pairingCredentialStore = credentialStore(context),
                // Stable identity so clients reconnect by serverDeviceId (survives a
                // TCP port change) instead of the ephemeral last-known port.
                serverDeviceId = stableServerDeviceId(appContext),
                serverName = Build.MODEL ?: "Android",
            )
            return LanRepository(service).also {
                repository = it
                Log.i(TAG, "get: creating NEW repository@${System.identityHashCode(it).toString(16)} (service instanceId may follow in core logs)")
            }
        }
    }

    /**
     * Returns a process-stable server identity, generated once and persisted in
     * SharedPreferences. SharedPreferences (not the DataStore that holds pairing
     * state) is used deliberately: its reads are synchronous, so [get] can stay
     * non-suspend, and this id is an independent small value with its own lifecycle.
     */
    private fun stableServerDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SERVER_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SERVER_DEVICE_ID, id).apply()
        return id
    }

    private const val DEVICE_ID_PREFS = "lanlink_device"
    private const val KEY_SERVER_DEVICE_ID = "lanlink.server.device_id"

    /** The current repository, or null if none has been created (or it was closed). */
    val current: LanRepository?
        get() = synchronized(lock) { repository }

    /**
     * Returns the process-wide credential store, backed by the same single
     * DataStore instance. Safe to call before [get]; the store is independent of
     * the repository's lifecycle.
     */
    fun credentialStore(context: Context): DataStorePairingCredentialStore {
        synchronized(lock) {
            credentialStoreInstance?.let { return it }
            val dataStore = context.applicationContext.pairingDataStore
            return DataStorePairingCredentialStore(dataStore).also { credentialStoreInstance = it }
        }
    }

    /**
     * Tears down the current session: disconnects, cancels the repository scope,
     * and clears the reference so the next [get] builds a clean instance. The
     * credential store is intentionally kept — it only reads persistent state.
     */
    fun close() {
        synchronized(lock) {
            val repo = repository
            if (repo == null) {
                Log.i(TAG, "close: no active repository")
                return
            }
            Log.i(TAG, "close: tearing down repository@${System.identityHashCode(repo).toString(16)}")
            repo.close()
            repository = null
        }
    }
}
