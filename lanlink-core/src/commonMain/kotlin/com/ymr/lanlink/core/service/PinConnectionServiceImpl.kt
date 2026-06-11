package com.ymr.lanlink.core.service

import com.ymr.lanlink.core.data.auth.InMemoryAuthProvider
import com.ymr.lanlink.core.data.discovery.DiscoveredPeer
import com.ymr.lanlink.core.data.pairing.InMemoryPairingCredentialStore
import com.ymr.lanlink.core.data.pairing.InMemoryPairingRegistry
import com.ymr.lanlink.core.data.proto.AuthProtocol
import com.ymr.lanlink.core.data.proto.PairingCredential
import com.ymr.lanlink.core.data.proto.decodeIssuedPairing
import com.ymr.lanlink.core.data.proto.decodePairingCredential
import com.ymr.lanlink.core.data.proto.encode
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.domain.pairing.PairingCredentialStore
import com.ymr.lanlink.core.domain.pairing.PairingRegistry
import com.ymr.lanlink.core.net.DiscoveryAdvertiser
import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.net.LanClient
import com.ymr.lanlink.core.net.LanNetworkFactory
import com.ymr.lanlink.core.net.LanServer
import com.ymr.lanlink.core.platform.deviceName
import com.ymr.lanlink.core.platform.ioDispatcher
import com.ymr.lanlink.core.platform.logger
import com.ymr.lanlink.core.platform.nowMillis
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalEncodingApi::class)
class PinConnectionServiceImpl(
    private val factory: LanNetworkFactory,
    private val discoveryTimeoutMs: Long = 30_000,
    // Server-side store of issued pairing records (1:N). Defaults to in-memory;
    // host App injects a persistent implementation for durability across restarts.
    private val pairingRegistry: PairingRegistry = InMemoryPairingRegistry(),
    private val serverDeviceId: String = "",
    private val serverName: String = "",
    // Direct-connect fast-path TCP connect timeout before falling back to UDP discovery.
    private val directConnectTimeoutMs: Long = 3_000,
    // Client-side store of the most-recent pairing credential blob (1:1). Defaults
    // to in-memory; host App injects a persistent implementation (SharedPreferences,
    // DataStore) so reconnectLastServer() survives process restarts. The service
    // auto-saves on issuance/refresh and auto-clears on PAIRING_REVOKED.
    private val pairingCredentialStore: PairingCredentialStore = InMemoryPairingCredentialStore(),
) : PinConnectionService {

    private val _connectionState = MutableStateFlow<PinConnectionState>(PinConnectionState.Idle)
    override val connectionState: StateFlow<PinConnectionState> = _connectionState.asStateFlow()

    private val _messageFlow = MutableSharedFlow<TypedMessage>(replay = 0)
    override val messageFlow: SharedFlow<TypedMessage> = _messageFlow.asSharedFlow()

    private val _eventFlow = MutableSharedFlow<PinConnectionEvent>(replay = 0)
    override val eventFlow: SharedFlow<PinConnectionEvent> = _eventFlow.asSharedFlow()

    private val _pairingActive = MutableStateFlow(false)
    override val pairingActive: StateFlow<Boolean> = _pairingActive.asStateFlow()

    @Volatile private var lanServer: LanServer? = null
    @Volatile private var lanClient: LanClient? = null
    @Volatile private var advertiser: DiscoveryAdvertiser? = null
    @Volatile private var scanner: DiscoveryScanner? = null
    @Volatile private var sessionScope: CoroutineScope? = null

    // Stable short identifier so every log line can be attributed to one specific
    // service instance. hashCode is process-stable, collision-tolerant for logging,
    // and needs no atomics/synchronization in commonMain.
    private val instanceId = hashCode().toString(16).takeLast(4)

    companion object {
        private const val TAG = "PinConnectionServiceImpl"
    }

    override fun startServer() {
        logger.i(TAG, "[$instanceId] startServer()")
        val scope = newScope()
        scope.launch {
            try {
                runServer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "startServer failed", e)
                _connectionState.value = PinConnectionState.Error(null, e.message ?: "startServer failed")
            }
        }
    }

    override fun startPairing(pin: String) {
        logger.i(TAG, "[$instanceId] startPairing()")
        validatePin(pin)
        val server = lanServer
        if (server == null) {
            logger.w(TAG, "[$instanceId] startPairing called before startServer")
            _connectionState.value = PinConnectionState.Error(null, "startPairing requires startServer first")
            return
        }
        server.setPairingPin(pin)
        _pairingActive.value = true
        logger.d(TAG, "[$instanceId] Pairing window opened")
    }

    override fun stopPairing() {
        logger.i(TAG, "[$instanceId] stopPairing()")
        // Closing the window does not tear down the session: connected peers and
        // token reconnects keep working.
        lanServer?.setPairingPin(null)
        _pairingActive.value = false
        logger.d(TAG, "[$instanceId] Pairing window closed")
    }

    override fun pairWithServer(pin: String) {
        logger.i(TAG, "[$instanceId] pairWithServer()")
        launchSession(pin, operation = "pairWithServer") { runClient(pin) }
    }

    override fun reconnectLastServer() {
        logger.i(TAG, "[$instanceId] reconnectLastServer()")
        val scope = newScope()
        scope.launch {
            try {
                val localData = pairingCredentialStore.load()
                if (localData == null) {
                    logger.w(TAG, "reconnectLastServer: no stored pairing")
                    _connectionState.value = PinConnectionState.Error(null, "no stored pairing")
                    return@launch
                }
                runDirectConnect(localData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "reconnectLastServer failed", e)
                _connectionState.value = PinConnectionState.Error(null, e.message ?: "reconnectLastServer failed")
            }
        }
    }

    override fun disconnect() {
        logger.i(TAG, "[$instanceId] disconnect()")
        sessionScope?.cancel()
        sessionScope = null
        releaseResources()
        _connectionState.value = PinConnectionState.Idle
    }

    override suspend fun send(type: Int, data: ByteArray) {
        val client = lanClient
        val server = lanServer
        when {
            client != null -> client.send(type, data)
            server != null -> server.send(type, data)
            else -> throw IllegalStateException("Not connected")
        }
    }

    private suspend fun CoroutineScope.runServer() {
        // Listening (server up, no peer yet). No PIN on the wire here — the PIN
        // window is a separate concern opened via startPairing().
        _connectionState.value = PinConnectionState.Connecting(null, isServer = true)

        val server = factory.createServer(
            pairingRegistry = pairingRegistry,
            serverDeviceId = serverDeviceId,
            serverName = serverName,
        ).also { lanServer = it }
        val port = server.start()
        logger.i(TAG, "[$instanceId] runServer: TCP server started on port $port (server=${server::class.simpleName})")

        factory.createAdvertiser(port, serverDeviceId).also {
            advertiser = it
            it.start()
        }
        logger.i(TAG, "[$instanceId] runServer: UDP advertiser created broadcasting servicePort=$port (advertiser=${advertiser?.let { it::class.simpleName }})")

        launch { awaitAuthenticatedPeer(server) }
        launch { pipeMessages(server.messages) }
        launch { awaitServerDisconnect(server) }
    }

    private suspend fun awaitAuthenticatedPeer(server: LanServer) {
        server.authenticatedPeers.collect { peer ->
            logger.d(TAG, "Peer authenticated: ${peer.name}")
            markConnected(null, isServer = true, peerName = peer.name)
        }
    }

    private suspend fun awaitServerDisconnect(server: LanServer) {
        var hadPeer = false
        server.connectedPeers.collect { peers ->
            when {
                hadPeer && peers.isEmpty() -> {
                    hadPeer = false
                    markDisconnected()
                }
                peers.isNotEmpty() -> hadPeer = true
            }
        }
    }

    private suspend fun CoroutineScope.runClient(pin: String) {
        _connectionState.value = PinConnectionState.Discovering(pin)

        val peer = discoverPeer()
        if (peer == null) {
            logger.w(TAG, "Discovery timeout")
            fail(pin, "Discovery timeout")
            return
        }
        logger.d(TAG, "Peer found: ${peer.name} at ${peer.host}:${peer.port}")

        _connectionState.value = PinConnectionState.Connecting(pin, isServer = false)
        val client = factory.createClient().also { lanClient = it }
        client.connect(PeerInfo(peer.name, peer.host, peer.port))

        val result = client.authenticate(deviceName(), InMemoryAuthProvider(pin).getCredentials()!!)
        if (!result.success) {
            logger.w(TAG, "Auth failed: ${result.message}")
            fail(pin, result.message)
            client.disconnect()
            lanClient = null
            return
        }

        client.startReadLoop()
        markConnected(pin, isServer = false, peerName = peer.name)
        logger.d(TAG, "Connected to ${peer.name}")

        // First-pairing credential issuance: if the server minted a pairing
        // record it travels back in customData. Assemble the opaque localData blob
        // and store it for future reconnectLastServer() calls.
        result.customData?.let { issuedBytes ->
            val localData = buildLocalData(issuedBytes, peer.host, peer.port)
            // Auto-persist so reconnectLastServer() works without the App handling
            // the blob; the event is still emitted for observability.
            pairingCredentialStore.save(localData)
            _eventFlow.emit(PinConnectionEvent.PairingCredentialIssued)
            logger.d(TAG, "Pairing credential issued")
        }

        launch { pipeMessages(client.messages) }
        launch { awaitClientDisconnect(client) }
    }

    private fun buildLocalData(issuedBytes: ByteArray, host: String, port: Int): String {
        val issued = decodeIssuedPairing(issuedBytes)
        val credential = PairingCredential(
            version = 1,
            scheme = AuthProtocol.SCHEME_HMAC_SHA256_V1,
            pairingId = issued.pairingId,
            secret = issued.secret,
            serverDeviceId = issued.serverDeviceId,
            serverName = issued.serverName,
            lastHost = host,
            lastPort = port,
            pairedAt = nowMillis(),
        )
        return Base64.encode(credential.encode())
    }

    private suspend fun CoroutineScope.runDirectConnect(localData: String) {
        val credential = try {
            decodePairingCredential(Base64.decode(localData)).also {
                require(it.version == 1 && it.scheme == AuthProtocol.SCHEME_HMAC_SHA256_V1) { "invalid credential" }
                require(it.pairingId.isNotEmpty() && it.secret.isNotEmpty()) { "invalid credential" }
            }
        } catch (e: Exception) {
            logger.w(TAG, "Invalid credential blob", e)
            _connectionState.value = PinConnectionState.Error(null, "invalid credential")
            return
        }

        _connectionState.value = PinConnectionState.Connecting(null, isServer = false)

        // Fast path: direct connect to the credential's last known address.
        var connectedPeer: PeerInfo? = null
        var connectedClient: LanClient? = null
        if (credential.lastHost.isNotEmpty() && credential.lastPort > 0) {
            val fastPeer = PeerInfo(credential.serverName, credential.lastHost, credential.lastPort)
            when (val outcome = tryTokenConnect(fastPeer, credential)) {
                is DirectOutcome.Connected -> {
                    connectedPeer = fastPeer
                    connectedClient = outcome.client
                }
                is DirectOutcome.Revoked -> {
                    onPairingRevoked()
                    return
                }
                is DirectOutcome.Unreachable -> {
                    logger.d(TAG, "Direct fast path failed (${outcome.reason}); falling back to discovery")
                }
            }
        }

        // Fallback path: UDP discovery. Match the server by its stable identity
        // (serverDeviceId) so a restart on a new TCP port is still found; fall back
        // to the legacy serverName match for old credentials that lack an id.
        if (connectedPeer == null) {
            _connectionState.value = PinConnectionState.Discovering(null)
            val candidates = discoverPeerCandidates(credential)
            if (candidates.isEmpty()) {
                logger.w(TAG, "[$instanceId] Discovery timeout during direct connect (serverDeviceId=${credential.serverDeviceId.ifEmpty { "<none>" }})")
                _connectionState.value = PinConnectionState.Error(null, "Discovery timeout")
                return
            }
            // Try candidates in order (freshest first). Each Unreachable just moves on
            // to the next live address advertising the same identity.
            var lastUnreachable: String? = null
            for (discovered in candidates) {
                val discoveredPeer = PeerInfo(discovered.name, discovered.host, discovered.port)
                logger.i(TAG, "[$instanceId] Direct fallback trying ${discovered.serverDeviceId.ifEmpty { "<no-id>" }}@${discovered.host}:${discovered.port}")
                when (val outcome = tryTokenConnect(discoveredPeer, credential)) {
                    is DirectOutcome.Connected -> {
                        connectedPeer = discoveredPeer
                        connectedClient = outcome.client
                    }
                    is DirectOutcome.Revoked -> {
                        onPairingRevoked()
                        return
                    }
                    is DirectOutcome.Unreachable -> {
                        logger.d(TAG, "[$instanceId] Candidate ${discovered.host}:${discovered.port} unreachable: ${outcome.reason}")
                        lastUnreachable = outcome.reason
                        continue
                    }
                }
                if (connectedPeer != null) break
            }
            if (connectedPeer == null) {
                _connectionState.value = PinConnectionState.Error(null, lastUnreachable ?: "connect failed")
                return
            }
        }

        val peer = connectedPeer
        val client = connectedClient
        if (peer == null || client == null) {
            // Unreachable: every Connected branch sets both together. Guard
            // instead of !! so a future refactor fails loudly rather than NPEs.
            _connectionState.value = PinConnectionState.Error(null, "connect failed")
            client?.disconnect()
            return
        }
        lanClient = client
        client.startReadLoop()
        markConnected(null, isServer = false, peerName = peer.name)
        logger.d(TAG, "Direct-connected to ${peer.name}")

        // If the live address differs from the stored one, refresh the credential.
        if (peer.host != credential.lastHost || peer.port != credential.lastPort) {
            val updated = credential.copy(lastHost = peer.host, lastPort = peer.port)
            val updatedBlob = Base64.encode(updated.encode())
            // Auto-persist the refreshed blob so the next reconnect uses the live
            // address; the event is still emitted for observability.
            pairingCredentialStore.save(updatedBlob)
            _eventFlow.emit(PinConnectionEvent.PairingCredentialUpdated)
            logger.i(TAG, "[$instanceId] Pairing credential address refreshed ${credential.lastHost}:${credential.lastPort} -> ${peer.host}:${peer.port}")
        }

        launch { pipeMessages(client.messages) }
        launch { awaitClientDisconnect(client) }
    }

    private sealed class DirectOutcome {
        // Carries the connected [LanClient] so the caller owns the live instance
        // directly, instead of reaching back through a shared mutable field +
        // !! (review MINOR-1/2).
        data class Connected(val client: LanClient) : DirectOutcome()
        object Revoked : DirectOutcome()
        data class Unreachable(val reason: String) : DirectOutcome()
    }

    /**
     * Attempts a TCP connect to [peer] (bounded by [directConnectTimeoutMs]) and
     * the token handshake against [credential]. A network failure or a server
     * proof mismatch is [DirectOutcome.Unreachable] (caller may try another peer);
     * a PAIRING_REVOKED verdict is definitive ([DirectOutcome.Revoked]).
     */
    private suspend fun tryTokenConnect(peer: PeerInfo, credential: PairingCredential): DirectOutcome {
        val client = factory.createClient()
        var success = false
        try {
            try {
                withTimeout(directConnectTimeoutMs) { client.connect(PeerInfo(peer.name, peer.host, peer.port)) }
            } catch (e: TimeoutCancellationException) {
                // OUR OWN fast-path timeout (the inner client.connect retried past the
                // budget). This is NOT external scope cancellation — must NOT be
                // rethrown, or the whole reconnect coroutine dies silently and never
                // falls back to discovery (it leaves the UI stuck on "Connecting").
                return DirectOutcome.Unreachable("connect timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return DirectOutcome.Unreachable("connect failed: ${e.message}")
            }
            if (client.connectionState.value !is ConnectionState.Connected) {
                return DirectOutcome.Unreachable("connect failed")
            }

            val result = try {
                client.authenticateWithCredential(deviceName(), credential.pairingId, credential.secret)
            } catch (e: TimeoutCancellationException) {
                // A connected-but-stalled server can time out the handshake read.
                // Same rule: treat as unreachable, never as scope cancellation.
                return DirectOutcome.Unreachable("handshake timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return DirectOutcome.Unreachable("handshake failed: ${e.message}")
            }
            return if (result.success) {
                success = true
                DirectOutcome.Connected(client)
            } else if (result.message == AuthProtocol.REASON_PAIRING_REVOKED) {
                DirectOutcome.Revoked
            } else {
                DirectOutcome.Unreachable(result.message)
            }
        } finally {
            // Any non-success exit (including cancellation) must release the
            // socket this method opened (review MINOR-1).
            if (!success) client.disconnect()
        }
    }

    private suspend fun onPairingRevoked() {
        logger.w(TAG, "Pairing revoked by server")
        // Drop the stale credential so reconnectLastServer() no longer offers it;
        // the App observes AuthFailed and falls back to PIN pairing.
        pairingCredentialStore.clear()
        _connectionState.value = PinConnectionState.Error(null, AuthProtocol.REASON_PAIRING_REVOKED)
        _eventFlow.emit(PinConnectionEvent.AuthFailed(AuthProtocol.REASON_PAIRING_REVOKED))
    }

    /**
     * Discovers the target server for a reconnect, returning matching peers ordered
     * best-first so the caller can try the next candidate if the first is unreachable.
     *
     * - When [credential] carries a non-empty serverDeviceId, waits for peers whose
     *   broadcast id matches and returns those (identity match: survives a port change).
     * - Otherwise (legacy credential / legacy server with no id on the wire), falls
     *   back to the old behaviour: prefer a name match, else any discovered peer.
     */
    private suspend fun discoverPeerCandidates(credential: PairingCredential): List<DiscoveredPeer> {
        val discovery = factory.createScanner().also { scanner = it }
        discovery.start()
        val targetId = credential.serverDeviceId
        return if (targetId.isNotEmpty()) {
            withTimeoutOrNull(discoveryTimeoutMs) {
                discovery.discoveredPeers.first { peers -> peers.any { it.serverDeviceId == targetId } }
                    .filter { it.serverDeviceId == targetId }
            } ?: emptyList()
        } else {
            withTimeoutOrNull(discoveryTimeoutMs) {
                val peers = discovery.discoveredPeers.first { it.isNotEmpty() }
                val preferred = peers.filter { it.name == credential.serverName }
                if (preferred.isNotEmpty()) preferred else peers
            } ?: emptyList()
        }
    }

    private suspend fun discoverPeer(): DiscoveredPeer? {
        val discovery = factory.createScanner().also { scanner = it }
        discovery.start()
        logger.d(TAG, "Discovery started, waiting for peer (timeout: ${discoveryTimeoutMs}ms)")
        return withTimeoutOrNull(discoveryTimeoutMs) {
            discovery.discoveredPeers.first { it.isNotEmpty() }.first()
        }
    }

    private suspend fun awaitClientDisconnect(client: LanClient) {
        client.connectionState.collect { state ->
            if (state is ConnectionState.Idle && _connectionState.value is PinConnectionState.Connected) {
                markDisconnected()
            }
        }
    }

    /**
     * Validates the pin, starts a fresh session scope, and runs [block] on it with
     * uniform error handling: cancellation propagates, any other failure becomes an
     * [PinConnectionState.Error].
     */
    private fun launchSession(pin: String, operation: String, block: suspend CoroutineScope.() -> Unit) {
        validatePin(pin)
        val scope = newScope()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "$operation failed", e)
                _connectionState.value = PinConnectionState.Error(pin, e.message ?: "$operation failed")
            }
        }
    }

    private fun newScope(): CoroutineScope {
        logger.i(TAG, "[$instanceId] newScope: new session starting, releasing previous resources")
        sessionScope?.cancel()
        // The TCP server and discovery components own their own scopes, so
        // cancelling sessionScope does NOT stop them — they must be released
        // explicitly here, or a second startServer() leaks the previous server +
        // advertiser as a ghost instance that keeps accepting and broadcasting
        // its old port until the process dies (clients then discover and
        // connect to the stale one).
        releaseResources()
        return CoroutineScope(ioDispatcher + SupervisorJob()).also { sessionScope = it }
    }

    /** Stops and clears every live network resource of the current session. */
    private fun releaseResources() {
        // Identify exactly which resources are alive so a session switch makes any
        // ghost server/advertiser that should have died visible in logcat.
        fun id(o: Any?): String = o?.let { it::class.simpleName + "@" + it.hashCode().toString(16).takeLast(4) } ?: "none"
        logger.i(
            TAG,
            "[$instanceId] releaseResources: stopping server=${id(lanServer)}, advertiser=${id(advertiser)}, scanner=${id(scanner)}, client=${id(lanClient)}"
        )
        lanServer?.let {
            logger.i(TAG, "[$instanceId] releaseResources: stopping server ${id(it)}")
            it.stop()
        }
        lanServer = null
        lanClient?.let {
            logger.i(TAG, "[$instanceId] releaseResources: stopping client ${id(it)}")
            it.disconnect()
        }
        lanClient = null
        advertiser?.let {
            logger.i(TAG, "[$instanceId] releaseResources: stopping advertiser ${id(it)}")
            it.stop()
        }
        advertiser = null
        scanner?.let {
            logger.i(TAG, "[$instanceId] releaseResources: stopping scanner ${id(it)}")
            it.stop()
        }
        scanner = null
        _pairingActive.value = false
    }

    private fun validatePin(pin: String) {
        require(pin.length == 6 && pin.all { it.isDigit() }) {
            "PIN must be exactly 6 digits"
        }
    }

    private suspend fun pipeMessages(messages: SharedFlow<Pair<Int, ByteArray>>) {
        messages.collect { (type, bytes) ->
            _messageFlow.emit(TypedMessage(type, bytes))
        }
    }

    private suspend fun markConnected(pin: String?, isServer: Boolean, peerName: String) {
        _connectionState.value = PinConnectionState.Connected(pin, isServer, peerName)
        _eventFlow.emit(PinConnectionEvent.PeerConnected(peerName))
    }

    private suspend fun markDisconnected() {
        _eventFlow.emit(PinConnectionEvent.PeerDisconnected)
        _connectionState.value = PinConnectionState.Idle
    }

    private suspend fun fail(pin: String, reason: String) {
        _connectionState.value = PinConnectionState.Error(pin, reason)
        _eventFlow.emit(PinConnectionEvent.AuthFailed(reason))
    }
}
