package com.ymr.lanlink.data.pairing

import android.content.Context
import com.ymr.lanlink.core.domain.pairing.PairingCredentialStore

/**
 * SharedPreferences-backed [PairingCredentialStore] so the pairing credential
 * survives process restarts and reconnectLastServer() works across launches.
 *
 * NOTE: the blob is sensitive (it carries the HMAC secret). A production app
 * should use EncryptedSharedPreferences or the Android Keystore; plain prefs are
 * used here for demo simplicity only.
 */
class SharedPrefsPairingCredentialStore(
    context: Context,
    prefsName: String = "lanlink_prefs",
    private val key: String = "pairing_credential",
) : PairingCredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun save(localData: String) {
        prefs.edit().putString(key, localData).apply()
    }

    override suspend fun load(): String? = prefs.getString(key, null)

    override suspend fun clear() {
        prefs.edit().remove(key).apply()
    }

    /** Synchronous peek for UI affordances (e.g. enabling the reconnect button). */
    fun hasCredential(): Boolean = prefs.contains(key)
}
