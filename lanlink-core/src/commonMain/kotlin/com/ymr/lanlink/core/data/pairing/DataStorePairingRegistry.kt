package com.ymr.lanlink.core.data.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ymr.lanlink.core.data.proto.StoredPairingRecord
import com.ymr.lanlink.core.data.proto.decodeStoredPairingRecord
import com.ymr.lanlink.core.data.proto.encode
import com.ymr.lanlink.core.domain.pairing.PairingRecord
import com.ymr.lanlink.core.domain.pairing.PairingRegistry
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first

/**
 * Jetpack DataStore (Preferences) backed [PairingRegistry], so server-side
 * pairing records survive process restarts and token reconnection keeps working.
 *
 * Each record is stored under `lanlink.pairing.<pairingId>` as
 * Base64(protobuf(record)); [all] enumerates by that key prefix, so no separate
 * index key is maintained. Writes go through [DataStore.updateData] (via `edit`)
 * for transactional, last-write-wins semantics.
 *
 * The host App owns the [DataStore] instance and is responsible for its file
 * path on each platform. DataStore mandates a single active instance per file
 * for the whole process — the App must hold it as a process-wide singleton.
 *
 * The pairing secret is sensitive and DataStore writes it to disk in plaintext.
 * Apps with stronger requirements should supply an encrypted [PairingRegistry]
 * implementation instead.
 */
class DataStorePairingRegistry(
    private val dataStore: DataStore<Preferences>,
) : PairingRegistry {

    override suspend fun save(record: PairingRecord) {
        val encoded = Base64.encode(record.toStored().encode())
        dataStore.edit { prefs ->
            prefs[keyFor(record.pairingId)] = encoded
        }
    }

    override suspend fun find(pairingId: String): PairingRecord? {
        val key = keyFor(pairingId)
        val encoded = dataStore.data.first()[key] ?: return null
        return decode(encoded) ?: run {
            // Corrupt entry: drop it so it stops failing future reads.
            dataStore.edit { it.remove(key) }
            null
        }
    }

    override suspend fun remove(pairingId: String) {
        dataStore.edit { it.remove(keyFor(pairingId)) }
    }

    override suspend fun all(): List<PairingRecord> =
        dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith(KEY_PREFIX) }
            .mapNotNull { (_, value) -> (value as? String)?.let { decode(it) } }

    private fun decode(encoded: String): PairingRecord? = try {
        decodeStoredPairingRecord(Base64.decode(encoded)).toDomain()
    } catch (e: IllegalArgumentException) {
        // Bad Base64 and protobuf SerializationException (a subtype) both land
        // here: corrupt data reads as null. Anything unexpected propagates.
        null
    }

    private companion object {
        const val KEY_PREFIX = "lanlink.pairing."
        fun keyFor(pairingId: String): Preferences.Key<String> =
            stringPreferencesKey("$KEY_PREFIX$pairingId")
    }
}

private fun PairingRecord.toStored() = StoredPairingRecord(
    pairingId = pairingId,
    secret = secret,
    clientDeviceName = clientDeviceName,
    pairedAt = pairedAt,
    lastSeenAt = lastSeenAt,
)

private fun StoredPairingRecord.toDomain() = PairingRecord(
    pairingId = pairingId,
    secret = secret,
    clientDeviceName = clientDeviceName,
    pairedAt = pairedAt,
    lastSeenAt = lastSeenAt,
)
