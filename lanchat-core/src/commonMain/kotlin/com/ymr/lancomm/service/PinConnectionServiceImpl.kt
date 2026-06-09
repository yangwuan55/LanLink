package com.ymr.lancomm.service

import com.ymr.lancomm.data.auth.InMemoryAuthProvider
import com.ymr.lancomm.data.discovery.DiscoveredPeer
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import com.ymr.lancomm.net.DiscoveryAdvertiser
import com.ymr.lancomm.net.DiscoveryScanner
import com.ymr.lancomm.net.LanClient
import com.ymr.lancomm.net.LanNetworkFactory
import com.ymr.lancomm.net.LanServer
import com.ymr.lancomm.platform.deviceName
import com.ymr.lancomm.platform.ioDispatcher
import com.ymr.lancomm.platform.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PinConnectionServiceImpl(
    private val factory: LanNetworkFactory,
    private val discoveryTimeoutMs: Long = 30_000
) : PinConnectionService {

    private val _connectionState = MutableStateFlow<PinConnectionState>(PinConnectionState.Idle)
    override val connectionState: StateFlow<PinConnectionState> = _connectionState.asStateFlow()

    private val _messageFlow = MutableSharedFlow<TypedMessage>(replay = 0)
    override val messageFlow: SharedFlow<TypedMessage> = _messageFlow.asSharedFlow()

    private val _eventFlow = MutableSharedFlow<PinConnectionEvent>(replay = 0)
    override val eventFlow: SharedFlow<PinConnectionEvent> = _eventFlow.asSharedFlow()

    @Volatile private var lanServer: LanServer? = null
    @Volatile private var lanClient: LanClient? = null
    @Volatile private var advertiser: DiscoveryAdvertiser? = null
    @Volatile private var scanner: DiscoveryScanner? = null
    @Volatile private var sessionScope: CoroutineScope? = null

    companion object {
        private const val TAG = "PinConnectionServiceImpl"
    }

    override fun startServer(pin: String) {
        launchSession(pin, operation = "startServer") { runServer(pin) }
    }

    override fun connectServer(pin: String) {
        launchSession(pin, operation = "connectServer") { runClient(pin) }
    }

    override fun disconnect() {
        logger.d(TAG, "disconnect() called")
        sessionScope?.cancel()
        sessionScope = null
        lanServer?.stop()
        lanServer = null
        lanClient?.disconnect()
        lanClient = null
        advertiser?.stop()
        advertiser = null
        scanner?.stop()
        scanner = null
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

    private suspend fun CoroutineScope.runServer(pin: String) {
        _connectionState.value = PinConnectionState.Connecting(pin, isServer = true)

        val server = factory.createServer(pin).also { lanServer = it }
        val port = server.start()
        logger.d(TAG, "TCP server started on port $port")

        factory.createAdvertiser(port).also {
            advertiser = it
            it.start()
        }
        logger.d(TAG, "UDP discovery broadcasting on port $port")

        launch { awaitAuthenticatedPeer(pin, server) }
        launch { pipeMessages(server.messages) }
        launch { awaitServerDisconnect(server) }
    }

    private suspend fun awaitAuthenticatedPeer(pin: String, server: LanServer) {
        server.authenticatedPeers.collect { peer ->
            logger.d(TAG, "Peer authenticated: ${peer.name}")
            markConnected(pin, isServer = true, peerName = peer.name)
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

        launch { pipeMessages(client.messages) }
        launch { awaitClientDisconnect(client) }
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
        sessionScope?.cancel()
        return CoroutineScope(ioDispatcher + SupervisorJob()).also { sessionScope = it }
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

    private suspend fun markConnected(pin: String, isServer: Boolean, peerName: String) {
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
