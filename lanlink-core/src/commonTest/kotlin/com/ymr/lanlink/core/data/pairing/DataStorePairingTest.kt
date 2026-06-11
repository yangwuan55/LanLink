package com.ymr.lanlink.core.data.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ymr.lanlink.core.domain.pairing.PairingRecord
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

/**
 * In-memory [DataStore] of [Preferences], mirroring the real DataStore contract
 * (a `data` flow that always carries the latest value + transactional
 * [updateData]). Lets multiple store objects share one backing state, so we can
 * simulate "process restart" by rebuilding the registry over the same fake.
 */
private class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = mutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}

@OptIn(ExperimentalEncodingApi::class)
class DataStorePairingTest {

    private fun record(
        id: String,
        secret: ByteArray = byteArrayOf(1, 2, 3, 0, -1, 127, -128),
        name: String = "device-$id",
    ) = PairingRecord(
        pairingId = id,
        secret = secret,
        clientDeviceName = name,
        pairedAt = 1_700_000_000_000L,
        lastSeenAt = 1_700_000_111_111L,
    )

    private fun assertRecordEquals(expected: PairingRecord, actual: PairingRecord?) {
        requireNotNull(actual)
        assertEquals(expected.pairingId, actual.pairingId)
        assertContentEquals(expected.secret, actual.secret)
        assertEquals(expected.clientDeviceName, actual.clientDeviceName)
        assertEquals(expected.pairedAt, actual.pairedAt)
        assertEquals(expected.lastSeenAt, actual.lastSeenAt)
    }

    // region registry

    @Test
    fun registry_save_then_find_roundTrip() = runTest {
        val registry = DataStorePairingRegistry(FakePreferencesDataStore())
        val rec = record("p1")
        registry.save(rec)
        assertRecordEquals(rec, registry.find("p1"))
    }

    @Test
    fun registry_save_overwrites_existing() = runTest {
        val registry = DataStorePairingRegistry(FakePreferencesDataStore())
        registry.save(record("p1", secret = byteArrayOf(9)))
        val updated = record("p1", secret = byteArrayOf(1, 2, 3), name = "renamed")
        registry.save(updated)
        assertRecordEquals(updated, registry.find("p1"))
        assertEquals(1, registry.all().size)
    }

    @Test
    fun registry_remove_deletes_record() = runTest {
        val registry = DataStorePairingRegistry(FakePreferencesDataStore())
        registry.save(record("p1"))
        registry.remove("p1")
        assertNull(registry.find("p1"))
        assertEquals(0, registry.all().size)
    }

    @Test
    fun registry_all_returns_every_record() = runTest {
        val registry = DataStorePairingRegistry(FakePreferencesDataStore())
        registry.save(record("p1"))
        registry.save(record("p2"))
        registry.save(record("p3"))
        val ids = registry.all().map { it.pairingId }.toSet()
        assertEquals(setOf("p1", "p2", "p3"), ids)
    }

    @Test
    fun registry_find_unknown_returns_null() = runTest {
        val registry = DataStorePairingRegistry(FakePreferencesDataStore())
        assertNull(registry.find("nope"))
    }

    @Test
    fun registry_remove_unknown_isNoop() = runTest {
        val registry = DataStorePairingRegistry(FakePreferencesDataStore())
        registry.save(record("p1"))
        registry.remove("nope") // must not throw
        assertEquals(1, registry.all().size)
    }

    @Test
    fun registry_wireFormat_isStable() = runTest {
        // Golden bytes pin the on-disk protobuf layout. The @ProtoNumber
        // assignments (1..5) are the persistence contract: records written by
        // older builds must keep decoding. If this test fails, a field number
        // or wire type changed — that breaks existing installs' stored data.
        val golden = byteArrayOf(
            0x0A, 0x02, 0x70, 0x31,             // 1: pairingId = "p1"
            0x12, 0x03, 0x01, 0x02, 0x03,       // 2: secret = [1, 2, 3]
            0x1A, 0x03, 0x64, 0x65, 0x76,       // 3: clientDeviceName = "dev"
            0x20, 0x01,                         // 4: pairedAt = 1 (varint)
            0x28, 0x02,                         // 5: lastSeenAt = 2 (varint)
        )
        val expected = PairingRecord("p1", byteArrayOf(1, 2, 3), "dev", 1L, 2L)
        val key = stringPreferencesKey("lanlink.pairing.p1")

        // Decode path: golden bytes laid down by an "old build" must read back.
        val seeded = FakePreferencesDataStore()
        seeded.updateData { mutablePreferencesOf(key to Base64.encode(golden)) }
        assertRecordEquals(expected, DataStorePairingRegistry(seeded).find("p1"))

        // Encode path: saving the same record must produce exactly those bytes.
        val fresh = FakePreferencesDataStore()
        DataStorePairingRegistry(fresh).save(expected)
        assertContentEquals(golden, Base64.decode(fresh.data.first()[key]!!))
    }

    @Test
    fun registry_find_corrupt_base64_returns_null() = runTest {
        val fake = FakePreferencesDataStore()
        // Seed a bad value under the registry's key layout.
        fake.updateData {
            mutablePreferencesOf(
                stringPreferencesKey("lanlink.pairing.bad") to "!!!not-base64!!!",
            )
        }
        val registry = DataStorePairingRegistry(fake)
        assertNull(registry.find("bad"))
        // The corrupt entry is dropped, so it no longer appears in all().
        assertEquals(0, registry.all().size)
    }

    // endregion

    // region credential store

    @Test
    fun credential_save_then_load() = runTest {
        val store = DataStorePairingCredentialStore(FakePreferencesDataStore())
        store.save("blob-1")
        assertEquals("blob-1", store.load())
    }

    @Test
    fun credential_save_overwrites() = runTest {
        val store = DataStorePairingCredentialStore(FakePreferencesDataStore())
        store.save("blob-1")
        store.save("blob-2")
        assertEquals("blob-2", store.load())
    }

    @Test
    fun credential_clear_then_load_null() = runTest {
        val store = DataStorePairingCredentialStore(FakePreferencesDataStore())
        store.save("blob-1")
        store.clear()
        assertNull(store.load())
    }

    // endregion

    // region persistence semantics (process-restart simulation)

    @Test
    fun registry_survives_instance_rebuild_over_same_dataStore() = runTest {
        val fake = FakePreferencesDataStore()
        val rec = record("p1")
        DataStorePairingRegistry(fake).save(rec)
        // New object, same backing store: mimics rebuilding after a restart.
        assertRecordEquals(rec, DataStorePairingRegistry(fake).find("p1"))
    }

    @Test
    fun credential_survives_instance_rebuild_over_same_dataStore() = runTest {
        val fake = FakePreferencesDataStore()
        DataStorePairingCredentialStore(fake).save("blob-1")
        assertEquals("blob-1", DataStorePairingCredentialStore(fake).load())
    }

    // endregion
}
