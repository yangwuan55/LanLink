package com.ymr.lanlink.core.service

import com.ymr.lanlink.core.crypto.TokenProofs
import com.ymr.lanlink.core.crypto.constantTimeEquals
import com.ymr.lanlink.core.data.discovery.DiscoveredPeer
import com.ymr.lanlink.core.data.proto.IssuedPairing
import com.ymr.lanlink.core.data.proto.encode
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.domain.pairing.PairingRecord
import com.ymr.lanlink.core.domain.pairing.PairingRegistry
import com.ymr.lanlink.core.net.AuthHandshakeResult
import com.ymr.lanlink.core.net.DiscoveryAdvertiser
import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.net.LanClient
import com.ymr.lanlink.core.net.LanNetworkFactory
import com.ymr.lanlink.core.net.LanServer
import kotlinx.coroutines.delay
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
class FakeLanServer : LanServer {
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

    // Current PIN pairing-window value: null = closed. Mirrors the real
    // TcpSocketServer gate so orchestration tests can assert window control.
    var pairingPin: String? = null
        private set

    override suspend fun start(): Int {
        started = true
        return boundPort
    }

    override fun setPairingPin(pin: String?) {
        pairingPin = pin
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
 * Holds the per-test shared pairing secrets so [FakeLanClient.authenticate] (PIN,
 * issuance) and [FakeLanClient.authenticateWithCredential] (token reconnect) can
 * exchange a credential exactly as a real server/client pair would. Models the
 * server's [PairingRegistry] as the source of truth for known pairings.
 */
class FakeServerWorld(
    val registry: PairingRegistry,
    val serverDeviceId: String = "srv-device",
    val serverName: String = "RemotePeer",
    val issueSecret: ByteArray,
    val issuePairingId: String,
)

/**
 * In-memory [LanClient]. [authSucceeds] controls the PIN handshake verdict.
 * When [world] is provided, the PIN handshake also "mints" a pairing record into
 * the registry and returns it as customData, and [authenticateWithCredential]
 * runs the real HMAC challenge-response against the registry.
 */
class FakeLanClient(
    private val authSucceeds: Boolean,
    private val world: FakeServerWorld? = null,
) : LanClient {
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

    // Token-handshake observability for negative-path assertions.
    var tokenPairingId: String? = null
    var clientProofSent = false

    override suspend fun connect(peer: PeerInfo) {
        connectedPeer = peer
        _connectionState.value = ConnectionState.Connecting
        // Successful TCP connect; transport reaches Connected before auth runs.
        _connectionState.value = ConnectionState.Connected(peer.name, false)
    }

    override suspend fun authenticate(deviceName: String, credentials: ByteArray): AuthHandshakeResult {
        authDeviceName = deviceName
        authCredentials = credentials
        if (!authSucceeds) return AuthHandshakeResult(false, "Invalid PIN")

        val w = world ?: return AuthHandshakeResult(true, "OK")
        // Mint + persist the pairing record (server side) and ship it back.
        w.registry.save(
            PairingRecord(
                pairingId = w.issuePairingId,
                secret = w.issueSecret,
                clientDeviceName = deviceName,
                pairedAt = 0L,
                lastSeenAt = 0L,
            )
        )
        val issued = IssuedPairing(
            pairingId = w.issuePairingId,
            secret = w.issueSecret,
            serverDeviceId = w.serverDeviceId,
            serverName = w.serverName,
        )
        return AuthHandshakeResult(true, "OK", customData = issued.encode())
    }

    override suspend fun authenticateWithCredential(
        deviceName: String,
        pairingId: String,
        secret: ByteArray,
    ): AuthHandshakeResult {
        authDeviceName = deviceName
        tokenPairingId = pairingId
        val w = world ?: return AuthHandshakeResult(false, "PAIRING_REVOKED")
        val record = w.registry.find(pairingId)
            ?: return AuthHandshakeResult(false, "PAIRING_REVOKED")

        // Emulate the wire challenge-response with real proofs and fresh nonces.
        val clientNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val serverNonce = byteArrayOf(16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
        val serverProof = TokenProofs.serverProof(record.secret, clientNonce, serverNonce)
        // Client verifies server proof (constant time) against the credential secret.
        val expectedServerProof = TokenProofs.serverProof(secret, clientNonce, serverNonce)
        if (!constantTimeEquals(serverProof, expectedServerProof)) {
            // Aborts WITHOUT sending a client proof.
            return AuthHandshakeResult(false, "SERVER_PROOF_MISMATCH")
        }
        clientProofSent = true
        val clientProof = TokenProofs.clientProof(secret, serverNonce, clientNonce)
        val expectedClientProof = TokenProofs.clientProof(record.secret, serverNonce, clientNonce)
        return if (constantTimeEquals(clientProof, expectedClientProof)) {
            AuthHandshakeResult(true, "OK")
        } else {
            AuthHandshakeResult(false, "INVALID_PROOF")
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

class FakeDiscoveryAdvertiser(val servicePort: Int, val serverDeviceId: String = "") : DiscoveryAdvertiser {
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
 *
 * [world] threads a shared pairing registry/secret through the fake client so the
 * issuance + token reconnect flow can be exercised end-to-end. [reachableHosts]
 * gates which hosts the fake client can TCP-connect to, so the direct fast-path
 * failure + discovery fallback can be simulated; null means all hosts reachable.
 */
class FakeLanNetworkFactory(
    private val authSucceeds: Boolean = true,
    private val peerToDiscover: DiscoveredPeer? = DiscoveredPeer("RemotePeer", "10.0.0.5", 4242),
    private val world: FakeServerWorld? = null,
    private val reachableHosts: Set<String>? = null,
    // When set, connecting to a host outside [reachableHosts] HANGS for this long
    // instead of failing fast. This models the real TcpSocketClient, whose internal
    // retry-with-backoff loop keeps a connect to a dead address alive past the
    // service's directConnectTimeoutMs, forcing the withTimeout to fire a
    // TimeoutCancellationException (regression guard for the silent-death bug).
    private val unreachableHangMs: Long? = null,
) : LanNetworkFactory {

    var server: FakeLanServer? = null
    val servers = mutableListOf<FakeLanServer>()
    var client: FakeLanClient? = null
    val clients = mutableListOf<FakeLanClient>()
    var advertiser: FakeDiscoveryAdvertiser? = null
    val advertisers = mutableListOf<FakeDiscoveryAdvertiser>()
    var scanner: FakeDiscoveryScanner? = null

    override fun createServer(
        pairingRegistry: PairingRegistry,
        serverDeviceId: String,
        serverName: String,
    ): LanServer = FakeLanServer().also {
        server = it
        servers.add(it)
    }

    override fun createClient(): LanClient =
        FakeLanClient(authSucceeds, world).let { c ->
            val gated = if (reachableHosts == null) c else GatedFakeLanClient(c, reachableHosts, unreachableHangMs)
            // Track the underlying fake for assertions.
            client = c
            clients.add(c)
            gated
        }

    override fun createAdvertiser(servicePort: Int, serverDeviceId: String): DiscoveryAdvertiser =
        FakeDiscoveryAdvertiser(servicePort, serverDeviceId).also {
            advertiser = it
            advertisers.add(it)
        }

    override fun createScanner(): DiscoveryScanner =
        FakeDiscoveryScanner(peerToDiscover).also { scanner = it }
}

/**
 * Wraps a [FakeLanClient] so connecting to a host outside [reachableHosts] fails
 * (stays Idle), modelling an unreachable last-known address that forces the
 * direct-connect orchestration to fall back to discovery.
 */
class GatedFakeLanClient(
    private val delegate: FakeLanClient,
    private val reachableHosts: Set<String>,
    private val unreachableHangMs: Long? = null,
) : LanClient by delegate {
    override suspend fun connect(peer: PeerInfo) {
        if (peer.host in reachableHosts) {
            delegate.connect(peer)
        } else if (unreachableHangMs != null) {
            // Unreachable AND slow: block past the caller's directConnectTimeoutMs so
            // its withTimeout fires a TimeoutCancellationException, exactly like the
            // real client retrying a refused address past the budget.
            delay(unreachableHangMs)
        } else {
            // Unreachable: leave the connection Idle so tryTokenConnect bails out.
            delegate.disconnect()
        }
    }
}
