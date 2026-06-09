package com.ymr.lanlink.core.service

import com.ymr.lanlink.core.data.discovery.DiscoveredPeer
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.net.AuthHandshakeResult
import com.ymr.lanlink.core.net.DiscoveryAdvertiser
import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.net.LanClient
import com.ymr.lanlink.core.net.LanNetworkFactory
import com.ymr.lanlink.core.net.LanServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [LanServer] used to drive [PinConnectionServiceImpl] orchestration
 * tests without any real sockets. Tests push to the public mutable flows to
 * simulate peer lifecycle events.
 */
class FakeLanServer(val pin: String) : LanServer {
    val _messages = MutableSharedFlow<Pair<Int, ByteArray>>(replay = 0, extraBufferCapacity = 16)
    override val messages: SharedFlow<Pair<Int, ByteArray>> = _messages.asSharedFlow()

    val _authenticatedPeers = MutableSharedFlow<PeerInfo>(replay = 0, extraBufferCapacity = 16)
    override val authenticatedPeers: SharedFlow<PeerInfo> = _authenticatedPeers.asSharedFlow()

    val _connectedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerInfo>> = _connectedPeers.asStateFlow()

    var started = false
    var stopped = false
    val sent = mutableListOf<Pair<Int, ByteArray>>()
    val boundPort = 4242

    override suspend fun start(): Int {
        started = true
        return boundPort
    }

    override suspend fun send(type: Int, data: ByteArray) {
        sent.add(type to data)
    }

    override fun stop() {
        stopped = true
        _connectedPeers.value = emptyList()
    }
}

/**
 * In-memory [LanClient]. [authSucceeds] controls the handshake verdict so a test
 * can drive both the success and failure orchestration branches.
 */
class FakeLanClient(private val authSucceeds: Boolean) : LanClient {
    val _messages = MutableSharedFlow<Pair<Int, ByteArray>>(replay = 0, extraBufferCapacity = 16)
    override val messages: SharedFlow<Pair<Int, ByteArray>> = _messages.asSharedFlow()

    val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var connectedPeer: PeerInfo? = null
    var authDeviceName: String? = null
    var authCredentials: ByteArray? = null
    var readLoopStarted = false
    var disconnected = false
    val sent = mutableListOf<Pair<Int, ByteArray>>()

    override suspend fun connect(peer: PeerInfo) {
        connectedPeer = peer
        _connectionState.value = ConnectionState.Connecting
    }

    override suspend fun authenticate(deviceName: String, credentials: ByteArray): AuthHandshakeResult {
        authDeviceName = deviceName
        authCredentials = credentials
        return if (authSucceeds) {
            AuthHandshakeResult(true, "OK")
        } else {
            AuthHandshakeResult(false, "Invalid PIN")
        }
    }

    override fun startReadLoop() {
        readLoopStarted = true
        _connectionState.value = ConnectionState.Connected(connectedPeer?.name ?: "peer", false)
    }

    override suspend fun send(type: Int, data: ByteArray) {
        sent.add(type to data)
    }

    override fun disconnect() {
        disconnected = true
        _connectionState.value = ConnectionState.Idle
    }
}

class FakeDiscoveryAdvertiser(val servicePort: Int) : DiscoveryAdvertiser {
    var started = false
    var stopped = false
    override fun start() { started = true }
    override fun stop() { stopped = true }
}

/**
 * In-memory [DiscoveryScanner]. By default it eagerly publishes [peerToDiscover]
 * on [start] so the service's discovery `first { it.isNotEmpty() }` resolves.
 * Set [peerToDiscover] to null to simulate a discovery timeout.
 */
class FakeDiscoveryScanner(private val peerToDiscover: DiscoveredPeer?) : DiscoveryScanner {
    val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    var started = false
    var stopped = false

    override fun start() {
        started = true
        if (peerToDiscover != null) {
            _discoveredPeers.value = listOf(peerToDiscover)
        }
    }

    override fun stop() { stopped = true }
}

/**
 * Fake [LanNetworkFactory] wiring the in-memory components. Exposes the created
 * instances so assertions can inspect orchestration side effects.
 */
class FakeLanNetworkFactory(
    private val authSucceeds: Boolean = true,
    private val peerToDiscover: DiscoveredPeer? = DiscoveredPeer("RemotePeer", "10.0.0.5", 4242)
) : LanNetworkFactory {

    var server: FakeLanServer? = null
    var client: FakeLanClient? = null
    var advertiser: FakeDiscoveryAdvertiser? = null
    var scanner: FakeDiscoveryScanner? = null

    override fun createServer(pin: String): LanServer =
        FakeLanServer(pin).also { server = it }

    override fun createClient(): LanClient =
        FakeLanClient(authSucceeds).also { client = it }

    override fun createAdvertiser(servicePort: Int): DiscoveryAdvertiser =
        FakeDiscoveryAdvertiser(servicePort).also { advertiser = it }

    override fun createScanner(): DiscoveryScanner =
        FakeDiscoveryScanner(peerToDiscover).also { scanner = it }
}
