package com.ymr.lanlink.core.service

import com.ymr.lanlink.core.crypto.TokenProofs
import com.ymr.lanlink.core.crypto.constantTimeEquals
import com.ymr.lanlink.core.data.discovery.DiscoveredPeer
import com.ymr.lanlink.core.data.pairing.InMemoryPairingCredentialStore
import com.ymr.lanlink.core.data.pairing.InMemoryPairingRegistry
import com.ymr.lanlink.core.data.proto.decodePairingCredential
import com.ymr.lanlink.core.domain.pairing.PairingCredentialStore
import com.ymr.lanlink.core.domain.pairing.PairingRecord
import com.ymr.lanlink.core.domain.pairing.PairingRegistry
import com.ymr.lanlink.core.platform.secureRandomBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Credential direct-connect orchestration tests, driven by [FakeLanNetworkFactory]
 * with a shared [FakeServerWorld] so issuance + HMAC token reconnect run end-to-end
 * without sockets. Covers the happy path, the three negative paths, and the
 * discovery fallback (plan §5 acceptance criteria 3–8).
 */
@OptIn(ExperimentalEncodingApi::class)
class DirectConnectServiceTest {

    private val pin = "123456"

    private suspend fun <T> awaitNotNull(timeoutMs: Long = 5_000, predicate: () -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            var v = predicate()
            while (v == null) {
                delay(10)
                v = predicate()
            }
            v
        }

    /**
     * Runs a first PIN pairing through the service and returns the issued localData.
     * When [store] is supplied it is injected so the issued credential is auto-saved
     * into it (so a later reconnectLastServer() against the same store finds it).
     */
    private suspend fun CoroutineScope.pairAndGetLocalData(
        registry: PairingRegistry,
        peer: DiscoveredPeer,
        secret: ByteArray,
        store: PairingCredentialStore = InMemoryPairingCredentialStore(),
    ): String {
        val world = FakeServerWorld(
            registry = registry,
            serverDeviceId = "srv-device",
            serverName = "RemotePeer",
            issueSecret = secret,
            issuePairingId = "pairing-1",
        )
        val factory = FakeLanNetworkFactory(authSucceeds = true, peerToDiscover = peer, world = world)
        val service = PinConnectionServiceImpl(factory, pairingRegistry = registry, pairingCredentialStore = store)
        val events = mutableListOf<PinConnectionEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }
        service.pairWithServer(pin)
        val issued = awaitNotNull {
            events.filterIsInstance<PinConnectionEvent.PairingCredentialIssued>().firstOrNull()
        }
        assertNotNull(issued, "expected PairingCredentialIssued")
        service.disconnect()
        collector.cancel()
        // The event no longer carries the blob; the injected store is the channel.
        return store.load() ?: error("credential not auto-stored")
    }

    @Test
    fun firstPairing_issues_credential_with_secret_and_address() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val localData = pairAndGetLocalData(registry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret)

        val cred = decodePairingCredential(Base64.decode(localData))
        assertEquals("pairing-1", cred.pairingId)
        assertEquals(32, cred.secret.size)
        assertEquals("srv-device", cred.serverDeviceId)
        assertEquals("10.0.0.5", cred.lastHost)
        assertEquals(4242, cred.lastPort)
        // Server registry holds the issued record (acceptance criterion 2).
        assertNotNull(registry.find("pairing-1"))
        Unit
    }

    @Test
    fun directConnect_succeeds_via_fast_path_when_server_online() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        // Pairing auto-saves the credential into this store; reconnect reads it back.
        val store = InMemoryPairingCredentialStore()
        pairAndGetLocalData(registry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        // Fresh factory, same registry (server still knows the pairing).
        val world = FakeServerWorld(registry, "srv-device", "RemotePeer", secret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.5", 4242),
            world = world,
        )
        val service = PinConnectionServiceImpl(factory, pairingRegistry = registry, pairingCredentialStore = store)

        service.reconnectLastServer()

        val connected = awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }
        assertNotNull(connected)
        assertFalse(connected!!.isServer)
        assertEquals(null, connected.pin) // no PIN on the direct path
        // Fast path used the credential's last host; no discovery scanner created.
        assertEquals(null, factory.scanner)
        service.disconnect()
    }

    @Test
    fun reconnectLastServer_errors_when_no_stored_pairing() = runBlocking {
        val factory = FakeLanNetworkFactory()
        // Empty store -> reconnect must bail with the documented reason.
        val service = PinConnectionServiceImpl(factory, pairingCredentialStore = InMemoryPairingCredentialStore())

        service.reconnectLastServer()

        val error = awaitNotNull { service.connectionState.value as? PinConnectionState.Error }
        assertNotNull(error)
        assertEquals("no stored pairing", error!!.reason)
        // No client/scanner created when there is nothing to reconnect to.
        assertEquals(null, factory.client)
        assertEquals(null, factory.scanner)
        service.disconnect()
    }

    @Test
    fun pairing_auto_stores_credential_and_reconnect_reads_it() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        val localData = pairAndGetLocalData(registry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        // Pairing populated the store with the same blob the event delivered.
        assertEquals(localData, store.load())
    }

    @Test
    fun directConnect_unknown_pairingId_emits_PAIRING_REVOKED() = runBlocking {
        // Pair against one registry, then point the server at an EMPTY registry so
        // find() returns null -> PAIRING_REVOKED.
        val pairedRegistry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        pairAndGetLocalData(pairedRegistry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        val emptyRegistry = InMemoryPairingRegistry()
        val world = FakeServerWorld(emptyRegistry, "srv-device", "RemotePeer", secret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.5", 4242),
            world = world,
        )
        val service = PinConnectionServiceImpl(factory, pairingRegistry = emptyRegistry, pairingCredentialStore = store)
        val events = mutableListOf<PinConnectionEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }

        service.reconnectLastServer()

        val failed = awaitNotNull {
            events.filterIsInstance<PinConnectionEvent.AuthFailed>().firstOrNull { it.reason == "PAIRING_REVOKED" }
        }
        assertNotNull(failed)
        // PAIRING_REVOKED auto-clears the stored credential. The clear happens in
        // onPairingRevoked() before AuthFailed is emitted, so by now it is gone.
        assertEquals(null, store.load())
        collector.cancel()
        service.disconnect()
    }

    @Test
    fun directConnect_serverProof_mismatch_aborts_without_client_proof() = runBlocking {
        val pairedRegistry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        pairAndGetLocalData(pairedRegistry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        // Server has a record under the same pairingId but a DIFFERENT secret, so
        // its serverProof won't verify against the credential's secret.
        val wrongSecret = ByteArray(32) { 0x55 }
        val wrongRegistry = InMemoryPairingRegistry()
        wrongRegistry.save(
            PairingRecord("pairing-1", wrongSecret, "c", 0L, 0L)
        )
        val world = FakeServerWorld(wrongRegistry, "srv-device", "RemotePeer", wrongSecret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            peerToDiscover = null, // no fallback peer; mismatch alone must end it
            world = world,
        )
        val service = PinConnectionServiceImpl(factory, pairingRegistry = wrongRegistry, discoveryTimeoutMs = 300, pairingCredentialStore = store)

        service.reconnectLastServer()

        // Ends in Error (fast path mismatch -> Unreachable -> discovery timeout).
        val error = awaitNotNull { service.connectionState.value as? PinConnectionState.Error }
        assertNotNull(error)
        // The fake client must NOT have sent a client proof after the bad server proof.
        val client = factory.clients.firstOrNull { it.tokenPairingId == "pairing-1" }
        assertNotNull(client)
        assertFalse(client!!.clientProofSent, "client must not send proof after server proof mismatch")
        service.disconnect()
    }

    @Test
    fun replay_old_proof_rejected_by_nonce_isolation() {
        // A proof captured under one nonce pair must not verify under fresh nonces.
        val secret = secureRandomBytes(32)
        val oldClientNonce = ByteArray(16) { 1 }
        val oldServerNonce = ByteArray(16) { 2 }
        val replayProof = TokenProofs.clientProof(secret, oldServerNonce, oldClientNonce)

        val newServerNonce = ByteArray(16) { 9 }
        val newClientNonce = ByteArray(16) { 8 }
        val expectedFresh = TokenProofs.clientProof(secret, newServerNonce, newClientNonce)

        assertFalse(
            constantTimeEquals(replayProof, expectedFresh),
            "replayed proof must not verify under fresh nonces",
        )
    }

    @Test
    fun directConnect_legacy_credential_without_serverDeviceId_uses_name_fallback() = runBlocking {
        // Old credential issued by a server that shipped no serverDeviceId: reconnect
        // must still work via the legacy name/any-peer discovery fallback.
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        // Pair against a world with an EMPTY serverDeviceId, so the credential lacks one.
        val pairWorld = FakeServerWorld(registry, serverDeviceId = "", serverName = "RemotePeer", issueSecret = secret, issuePairingId = "pairing-1")
        run {
            val factory = FakeLanNetworkFactory(authSucceeds = true, peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), world = pairWorld)
            val service = PinConnectionServiceImpl(factory, pairingRegistry = registry, pairingCredentialStore = store)
            val events = mutableListOf<PinConnectionEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }
            service.pairWithServer(pin)
            awaitNotNull { events.filterIsInstance<PinConnectionEvent.PairingCredentialIssued>().firstOrNull() }
            service.disconnect()
            collector.cancel()
        }
        assertEquals("", decodePairingCredential(Base64.decode(store.load()!!)).serverDeviceId)

        // Reconnect: stored last host dead; discovery offers a peer with NO id but a
        // matching name -> legacy fallback connects.
        val world = FakeServerWorld(registry, "", "RemotePeer", secret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.55", 6500),
            world = world,
            reachableHosts = setOf("10.0.0.55"),
        )
        val service = PinConnectionServiceImpl(factory, pairingRegistry = registry, pairingCredentialStore = store)

        service.reconnectLastServer()

        val connected = awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }
        assertNotNull(connected)
        assertEquals("RemotePeer", connected!!.peerName)
        service.disconnect()
    }

    @Test
    fun directConnect_matches_by_serverDeviceId_even_when_name_differs() = runBlocking {
        // Server restarted on a new port AND its broadcast name differs from what was
        // stored, but the stable serverDeviceId still matches -> reconnect must find it.
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        // Paired with serverDeviceId "srv-device" (FakeServerWorld default) at 10.0.0.5.
        pairAndGetLocalData(registry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        val world = FakeServerWorld(registry, "srv-device", "RemotePeer", secret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            // New address, DIFFERENT name, SAME serverDeviceId as the credential.
            peerToDiscover = DiscoveredPeer("RenamedHost", "10.0.0.88", 7100, "srv-device"),
            world = world,
            reachableHosts = setOf("10.0.0.88"),
        )
        val service = PinConnectionServiceImpl(factory, pairingRegistry = registry, pairingCredentialStore = store)
        val events = mutableListOf<PinConnectionEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }

        service.reconnectLastServer()

        val connected = awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }
        assertNotNull(connected)
        assertNotNull(awaitNotNull { factory.scanner?.takeIf { it.started } })
        // Connected to the new port discovered by identity, and credential refreshed.
        awaitNotNull { events.filterIsInstance<PinConnectionEvent.PairingCredentialUpdated>().firstOrNull() }
        val cred = decodePairingCredential(Base64.decode(store.load()!!))
        assertEquals("10.0.0.88", cred.lastHost)
        assertEquals(7100, cred.lastPort)
        service.disconnect()
        collector.cancel()
    }

    @Test
    fun directConnect_falls_back_to_discovery_when_fast_path_TIMES_OUT() = runBlocking {
        // Regression: the real TcpSocketClient retries a refused address with backoff,
        // so the fast-path connect does not fail fast — it runs past
        // directConnectTimeoutMs and the service's withTimeout fires a
        // TimeoutCancellationException. That exception MUST NOT be rethrown as scope
        // cancellation (which silently killed the reconnect coroutine and left the UI
        // stuck on "Connecting"); it must degrade to Unreachable and fall back to
        // discovery.
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        // Paired at 10.0.0.5 (the stored lastHost), which is now dead AND slow to fail.
        pairAndGetLocalData(registry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        val world = FakeServerWorld(registry, "srv-device", "RemotePeer", secret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            // Discovery resolves the server at a NEW address with the same identity.
            peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.77", 6000, "srv-device"),
            world = world,
            reachableHosts = setOf("10.0.0.77"),
            // The dead stored host hangs far longer than directConnectTimeoutMs below.
            unreachableHangMs = 60_000,
        )
        val service = PinConnectionServiceImpl(
            factory,
            pairingRegistry = registry,
            pairingCredentialStore = store,
            // Small budget so the hanging fast path times out quickly in-test.
            directConnectTimeoutMs = 200,
        )

        service.reconnectLastServer()

        // Must end Connected via discovery, NOT stuck forever on Connecting.
        val connected = awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }
        assertNotNull(connected, "fast-path timeout must fall back to discovery, not silently die")
        assertEquals("RemotePeer", connected!!.peerName)
        assertNotNull(awaitNotNull { factory.scanner?.takeIf { it.started } })
        val cred = decodePairingCredential(Base64.decode(store.load()!!))
        assertEquals("10.0.0.77", cred.lastHost)
        assertEquals(6000, cred.lastPort)
        service.disconnect()
    }

    @Test
    fun directConnect_falls_back_to_discovery_when_fast_path_unreachable() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val secret = secureRandomBytes(32)
        val store = InMemoryPairingCredentialStore()
        // Paired at 10.0.0.5 (the stored lastHost), but that address is now dead.
        pairAndGetLocalData(registry, DiscoveredPeer("RemotePeer", "10.0.0.5", 4242), secret, store)

        val world = FakeServerWorld(registry, "srv-device", "RemotePeer", secret, "pairing-1")
        val factory = FakeLanNetworkFactory(
            authSucceeds = true,
            // Discovery resolves the server at a NEW address, advertising the same
            // stable serverDeviceId so identity matching finds it.
            peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.77", 6000, "srv-device"),
            world = world,
            // Only the new address is reachable; the stored last host is dead.
            reachableHosts = setOf("10.0.0.77"),
        )
        val service = PinConnectionServiceImpl(factory, pairingRegistry = registry, pairingCredentialStore = store)
        val events = mutableListOf<PinConnectionEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }

        service.reconnectLastServer()

        val connected = awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }
        assertNotNull(connected)
        assertEquals("RemotePeer", connected!!.peerName)
        // Discovery scanner was used as the fallback.
        assertNotNull(awaitNotNull { factory.scanner?.takeIf { it.started } })
        // Address changed -> credential refreshed.
        val updated = awaitNotNull {
            events.filterIsInstance<PinConnectionEvent.PairingCredentialUpdated>().firstOrNull()
        }
        assertNotNull(updated)
        val cred = decodePairingCredential(Base64.decode(store.load()!!))
        assertEquals("10.0.0.77", cred.lastHost)
        assertEquals(6000, cred.lastPort)
        service.disconnect()
        collector.cancel()
    }
}
