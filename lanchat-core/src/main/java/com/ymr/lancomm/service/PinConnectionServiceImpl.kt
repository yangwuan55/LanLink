package com.ymr.lancomm.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.protobuf.ByteString
import com.ymr.lancomm.data.auth.InMemoryAuthProvider
import com.ymr.lancomm.data.discovery.DiscoveredPeer
import com.ymr.lancomm.data.discovery.UdpDiscoveryClient
import com.ymr.lancomm.data.discovery.UdpDiscoveryServer
import com.ymr.lancomm.data.socket.TcpSocketClient
import com.ymr.lancomm.data.socket.TcpSocketServer
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import com.ymr.lancomm.proto.AuthRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PinConnectionServiceImpl(
    private val context: Context,
    private val discoveryTimeoutMs: Long = 30_000
) : PinConnectionService {

    private val _connectionState = MutableStateFlow<PinConnectionState>(PinConnectionState.Idle)
    override val connectionState: StateFlow<PinConnectionState> = _connectionState.asStateFlow()

    private val _messageFlow = MutableSharedFlow<TypedMessage>(replay = 0)
    override val messageFlow: SharedFlow<TypedMessage> = _messageFlow.asSharedFlow()

    private val _eventFlow = MutableSharedFlow<PinConnectionEvent>(replay = 0)
    override val eventFlow: SharedFlow<PinConnectionEvent> = _eventFlow.asSharedFlow()

    @Volatile private var tcpServer: TcpSocketServer? = null
    @Volatile private var tcpClient: TcpSocketClient? = null
    @Volatile private var udpDiscoveryServer: UdpDiscoveryServer? = null
    @Volatile private var udpDiscoveryClient: UdpDiscoveryClient? = null
    @Volatile private var sessionScope: CoroutineScope? = null

    companion object {
        private const val TAG = "PinConnectionServiceImpl"
    }

    // ===== Public API =====

    override fun startServer(pin: String) {
        launchSession(pin, operation = "startServer") { runServer(pin) }
    }

    override fun connectServer(pin: String) {
        launchSession(pin, operation = "connectServer") { runClient(pin) }
    }

    override fun disconnect() {
        Log.d(TAG, "disconnect() called")
        sessionScope?.cancel()
        sessionScope = null
        tcpServer?.stop()
        tcpServer = null
        tcpClient?.disconnect()
        tcpClient = null
        udpDiscoveryServer?.stop()
        udpDiscoveryServer = null
        udpDiscoveryClient?.stop()
        udpDiscoveryClient = null
        _connectionState.value = PinConnectionState.Idle
    }

    override suspend fun send(type: Int, data: ByteArray) {
        val client = tcpClient
        val server = tcpServer
        when {
            client != null -> client.send(type, data)
            server != null -> server.send(type, data)
            else -> throw IllegalStateException("Not connected")
        }
    }

    // ===== Server role =====

    private suspend fun CoroutineScope.runServer(pin: String) {
        _connectionState.value = PinConnectionState.Connecting(pin, isServer = true)

        val server = TcpSocketServer(port = 0, authProvider = InMemoryAuthProvider(pin))
            .also { tcpServer = it }
        val port = server.start()
        Log.d(TAG, "TCP server started on port $port")

        UdpDiscoveryServer(context, port).also {
            udpDiscoveryServer = it
            it.start()
        }
        Log.d(TAG, "UDP discovery broadcasting on port $port")

        launch { awaitAuthenticatedPeer(pin, server) }
        launch { pipeMessages(server.messages) }
        launch { awaitServerDisconnect(server) }
    }

    private suspend fun awaitAuthenticatedPeer(pin: String, server: TcpSocketServer) {
        server.authenticatedPeers.collect { peer ->
            Log.d(TAG, "Peer authenticated: ${peer.name}")
            markConnected(pin, isServer = true, peerName = peer.name)
        }
    }

    private suspend fun awaitServerDisconnect(server: TcpSocketServer) {
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

    // ===== Client role =====

    private suspend fun CoroutineScope.runClient(pin: String) {
        _connectionState.value = PinConnectionState.Discovering(pin)

        val peer = discoverPeer()
        if (peer == null) {
            Log.w(TAG, "Discovery timeout")
            fail(pin, "Discovery timeout")
            return
        }
        Log.d(TAG, "Peer found: ${peer.name} at ${peer.host}:${peer.port}")

        _connectionState.value = PinConnectionState.Connecting(pin, isServer = false)
        val client = TcpSocketClient().also { tcpClient = it }
        client.connect(PeerInfo(peer.name, peer.host, peer.port))

        val authError = authenticate(client, pin)
        if (authError != null) {
            Log.w(TAG, "Auth failed: $authError")
            fail(pin, authError)
            client.disconnect()
            tcpClient = null
            return
        }

        client.startReadLoop()
        markConnected(pin, isServer = false, peerName = peer.name)
        Log.d(TAG, "Connected to ${peer.name}")

        launch { pipeMessages(client.messages) }
        launch { awaitClientDisconnect(client) }
    }

    private suspend fun discoverPeer(): DiscoveredPeer? {
        val discovery = UdpDiscoveryClient().also { udpDiscoveryClient = it }
        discovery.start()
        Log.d(TAG, "Discovery started, waiting for peer (timeout: ${discoveryTimeoutMs}ms)")
        return withTimeoutOrNull(discoveryTimeoutMs) {
            discovery.discoveredPeers.first { it.isNotEmpty() }.first()
        }
    }

    /** Runs the auth handshake; returns null on success or the failure message. */
    private suspend fun authenticate(client: TcpSocketClient, pin: String): String? {
        val channel = client.protobufChannel
            ?: throw IllegalStateException("No protobuf channel after connect")
        channel.sendAuthRequest(buildAuthRequest(pin))
        val response = channel.receiveAuthResponse()
        return response.message.takeIf { !response.success }
    }

    private suspend fun awaitClientDisconnect(client: TcpSocketClient) {
        client.connectionState.collect { state ->
            if (state is ConnectionState.Idle && _connectionState.value is PinConnectionState.Connected) {
                markDisconnected()
            }
        }
    }

    // ===== Shared helpers =====

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
                Log.e(TAG, "$operation failed", e)
                _connectionState.value = PinConnectionState.Error(pin, e.message ?: "$operation failed")
            }
        }
    }

    private fun newScope(): CoroutineScope {
        sessionScope?.cancel()
        return CoroutineScope(Dispatchers.IO + SupervisorJob()).also { sessionScope = it }
    }

    private fun validatePin(pin: String) {
        require(pin.length == 6 && pin.all { it.isDigit() }) {
            "PIN must be exactly 6 digits"
        }
    }

    private fun buildAuthRequest(pin: String): AuthRequest =
        AuthRequest.newBuilder()
            .setDeviceName(Build.MODEL)
            .setCredentials(ByteString.copyFrom(InMemoryAuthProvider(pin).getCredentials()!!))
            .build()

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
