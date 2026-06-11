package com.ymr.lanlink.core.data.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ymr.lanlink.core.domain.pairing.PairingCredentialStore
import kotlinx.coroutines.flow.first

/**
 * Jetpack DataStore (Preferences) backed [PairingCredentialStore], so the
 * client's single pairing-credential blob survives process restarts and
 * `reconnectLastServer()` keeps working across launches.
 *
 * The blob lives under one fixed key (`lanlink.credential`). The host App owns
 * the [DataStore] instance and its per-platform file path. DataStore mandates a
 * single active instance per file for the whole process — the App must hold it
 * as a process-wide singleton.
 *
 * The blob carries the HMAC secret and DataStore writes it to disk in plaintext.
 * Apps with stronger requirements should supply an encrypted
 * [PairingCredentialStore] implementation instead.
 */
class DataStorePairingCredentialStore(
    private val dataStore: DataStore<Preferences>,
) : PairingCredentialStore {

    override suspend fun save(localData: String) {
        dataStore.edit { it[KEY] = localData }
    }

    override suspend fun load(): String? = dataStore.data.first()[KEY]

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY: Preferences.Key<String> = stringPreferencesKey("lanlink.credential")
    }
}
