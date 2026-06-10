package com.ymr.lanlink.core.service

import com.ymr.lanlink.core.data.discovery.DiscoveredPeer
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Orchestration tests for [PinConnectionServiceImpl] driven entirely by an
 * in-memory [FakeLanNetworkFactory] — no sockets, no JVM/Android networking.
 * This is the design payoff of the [com.ymr.lanlink.core.net.LanNetworkFactory] seam:
 * the reusable orchestration is exercised in commonTest.
 *
 * Uses [runBlocking] (real time) because the service launches its session on the
 * platform [com.ymr.lanlink.core.platform.ioDispatcher] (a real dispatcher), so the
 * orchestration runs on background threads we wait on with real-time polling.
 */
class PinConnectionServiceImplTest {

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

    @Test
    fun startServer_starts_server_and_advertiser_and_reports_connected_on_authenticated_peer() = runBlocking {
        val factory = FakeLanNetworkFactory()
        val service = PinConnectionServiceImpl(factory)

        val events = mutableListOf<PinConnectionEvent>()
        // UNDISPATCHED so the collector subscribes to the replay=0 eventFlow before
        // connectServer/startServer can emit, otherwise early events (e.g. AuthFailed)
        // race ahead of subscription and are dropped (flaky under real dispatchers).
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }

        service.startServer()

        // Server + advertiser come up.
        assertNotNull(awaitNotNull { factory.server?.takeIf { it.started } })
        assertNotNull(awaitNotNull { factory.advertiser?.takeIf { it.started } })

        // State is Connecting(server) until a peer authenticates.
        val connectingState = awaitNotNull {
            (service.connectionState.value as? PinConnectionState.Connecting)?.takeIf { it.isServer }
        }
        assertNotNull(connectingState)

        // Simulate an authenticated peer. authenticatedPeers is a replay=0
        // SharedFlow, so re-emit until the service's collector has subscribed
        // and the state flips to Connected.
        val server = factory.server!!
        val peer = com.ymr.lanlink.core.domain.model.PeerInfo("RemotePeer", "10.0.0.5", 4242)
        server._connectedPeers.value = listOf(peer)
        val connected = withTimeoutOrNull(5_000) {
            var state: PinConnectionState.Connected? = null
            while (state == null) {
                server._authenticatedPeers.emit(peer)
                delay(20)
                state = service.connectionState.value as? PinConnectionState.Connected
            }
            state
        }
        assertNotNull(connected)
        assertTrue(connected!!.isServer)
        assertEquals("RemotePeer", connected.peerName)
        assertTrue(events.any { it is PinConnectionEvent.PeerConnected })

        service.disconnect()
        collector.cancel()
        assertTrue(server.stopped)
        assertEquals(PinConnectionState.Idle, service.connectionState.value)
    }

    @Test
    fun connectServer_discovers_authenticates_and_connects_on_success() = runBlocking {
        val factory = FakeLanNetworkFactory(authSucceeds = true)
        val service = PinConnectionServiceImpl(factory)

        service.pairWithServer(pin)

        // Scanner started and discovery resolves -> client connects + authenticates.
        assertNotNull(awaitNotNull { factory.scanner?.takeIf { it.started } })
        val client = awaitNotNull { factory.client?.takeIf { it.readLoopStarted } }
        assertNotNull(client)
        // Discovered peer was passed through to connect().
        assertEquals("RemotePeer", client!!.connectedPeer?.name)
        assertEquals("10.0.0.5", client.connectedPeer?.host)
        // Credentials passed are the 6-digit pin bytes.
        assertEquals(pin, client.authCredentials?.decodeToString())

        val connected = awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }
        assertNotNull(connected)
        assertFalse(connected!!.isServer)
        assertEquals("RemotePeer", connected.peerName)

        service.disconnect()
        assertTrue(client.disconnected)
    }

    @Test
    fun connectServer_reports_error_and_disconnects_client_on_auth_failure() = runBlocking {
        val factory = FakeLanNetworkFactory(authSucceeds = false)
        val service = PinConnectionServiceImpl(factory)

        val events = mutableListOf<PinConnectionEvent>()
        // UNDISPATCHED so the collector subscribes to the replay=0 eventFlow before
        // connectServer/startServer can emit, otherwise early events (e.g. AuthFailed)
        // race ahead of subscription and are dropped (flaky under real dispatchers).
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }

        service.pairWithServer(pin)

        val error = awaitNotNull { service.connectionState.value as? PinConnectionState.Error }
        assertNotNull(error)
        assertEquals("Invalid PIN", error!!.reason)
        // Client was disconnected after the failed handshake.
        assertNotNull(awaitNotNull { factory.client?.takeIf { it.disconnected } })
        assertTrue(events.any { it is PinConnectionEvent.AuthFailed })

        collector.cancel()
        service.disconnect()
    }

    @Test
    fun connectServer_reports_error_on_discovery_timeout() = runBlocking {
        val factory = FakeLanNetworkFactory(peerToDiscover = null)
        // Short timeout so the test is fast.
        val service = PinConnectionServiceImpl(factory, discoveryTimeoutMs = 300)

        service.pairWithServer(pin)

        val error = awaitNotNull { service.connectionState.value as? PinConnectionState.Error }
        assertNotNull(error)
        assertEquals("Discovery timeout", error!!.reason)
        // No client was ever created (discovery never resolved).
        assertEquals(null, factory.client)

        service.disconnect()
    }

    @Test
    fun messageFlow_passes_through_server_inbound_frames() = runBlocking {
        val factory = FakeLanNetworkFactory()
        val service = PinConnectionServiceImpl(factory)

        val received = mutableListOf<TypedMessage>()
        val collector = launch { service.messageFlow.toList(received) }

        service.startServer()
        val server = awaitNotNull { factory.server?.takeIf { it.started } }
        assertNotNull(server)

        // Give the pipeMessages collector time to subscribe before emitting.
        awaitNotNull { factory.server?.takeIf { it.started } }
        delay(200)
        server!!._messages.emit(7 to byteArrayOf(1, 2, 3))

        val msg = awaitNotNull { received.firstOrNull() }
        assertNotNull(msg)
        assertEquals(7, msg!!.type)
        assertEquals(listOf<Byte>(1, 2, 3), msg.payload.toList())

        collector.cancel()
        service.disconnect()
    }

    @Test
    fun messageFlow_passes_through_client_inbound_frames() = runBlocking {
        val factory = FakeLanNetworkFactory(
            peerToDiscover = DiscoveredPeer("RemotePeer", "10.0.0.5", 4242)
        )
        val service = PinConnectionServiceImpl(factory)

        val received = mutableListOf<TypedMessage>()
        val collector = launch { service.messageFlow.toList(received) }

        service.pairWithServer(pin)
        val client = awaitNotNull { factory.client?.takeIf { it.readLoopStarted } }
        assertNotNull(client)

        delay(200)
        client!!._messages.emit(9 to byteArrayOf(42))

        val msg = awaitNotNull { received.firstOrNull() }
        assertNotNull(msg)
        assertEquals(9, msg!!.type)
        assertEquals(listOf<Byte>(42), msg.payload.toList())

        collector.cancel()
        service.disconnect()
    }

    @Test
    fun client_disconnect_event_emitted_when_connection_drops() = runBlocking {
        val factory = FakeLanNetworkFactory()
        val service = PinConnectionServiceImpl(factory)

        val events = mutableListOf<PinConnectionEvent>()
        // UNDISPATCHED so the collector subscribes to the replay=0 eventFlow before
        // connectServer/startServer can emit, otherwise early events (e.g. AuthFailed)
        // race ahead of subscription and are dropped (flaky under real dispatchers).
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { service.eventFlow.toList(events) }

        service.pairWithServer(pin)
        val client = awaitNotNull { factory.client?.takeIf { it.readLoopStarted } }
        assertNotNull(client)
        awaitNotNull { service.connectionState.value as? PinConnectionState.Connected }

        // Simulate the underlying client connection dropping to Idle.
        client!!._connectionState.value = com.ymr.lanlink.core.domain.model.ConnectionState.Idle

        assertNotNull(awaitNotNull { events.firstOrNull { it is PinConnectionEvent.PeerDisconnected } })

        collector.cancel()
        service.disconnect()
    }

    @Test
    fun startPairing_opens_window_and_stopPairing_closes_it() = runBlocking {
        val factory = FakeLanNetworkFactory()
        val service = PinConnectionServiceImpl(factory)

        service.startServer()
        val server = awaitNotNull { factory.server?.takeIf { it.started } }
        assertNotNull(server)
        // Window starts closed and pairingActive is false.
        assertEquals(null, server!!.pairingPin)
        assertFalse(service.pairingActive.value)

        service.startPairing(pin)
        assertEquals(pin, awaitNotNull { server.pairingPin })
        assertTrue(awaitNotNull { service.pairingActive.value.takeIf { it } } ?: false)

        service.stopPairing()
        assertNotNull(awaitNotNull { if (server.pairingPin == null) true else null })
        assertFalse(service.pairingActive.value)

        service.disconnect()
    }

    @Test
    fun startPairing_before_startServer_enters_error() = runBlocking {
        val factory = FakeLanNetworkFactory()
        val service = PinConnectionServiceImpl(factory)

        service.startPairing(pin)

        val error = awaitNotNull { service.connectionState.value as? PinConnectionState.Error }
        assertNotNull(error)
        assertFalse(service.pairingActive.value)
        // No server was created.
        assertEquals(null, factory.server)

        service.disconnect()
    }

    @Test
    fun disconnect_resets_pairingActive() = runBlocking {
        val factory = FakeLanNetworkFactory()
        val service = PinConnectionServiceImpl(factory)

        service.startServer()
        awaitNotNull { factory.server?.takeIf { it.started } }
        service.startPairing(pin)
        assertTrue(awaitNotNull { service.pairingActive.value.takeIf { it } } ?: false)

        service.disconnect()
        assertFalse(service.pairingActive.value)
    }
}
