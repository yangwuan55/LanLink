package com.example.lanchat.data.repository

import android.content.Context
import android.util.Log
import com.ymr.lancomm.data.auth.NoOpAuthProvider
import com.ymr.lancomm.data.discovery.UdpDiscoveryServer
import com.ymr.lancomm.data.discovery.UdpDiscoveryClient
import com.ymr.lancomm.data.discovery.DiscoveredPeer
import com.ymr.lancomm.data.socket.TcpSocketClient
import com.ymr.lancomm.data.socket.TcpSocketServer
import com.ymr.lancomm.domain.auth.AuthProvider
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.proto.LanMessage as ProtoLanMessage
import com.ymr.lancomm.domain.model.PeerInfo
import com.ymr.lancomm.proto.AuthRequest
import com.ymr.lancomm.proto.AuthResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LanRepository(
    private val context: Context,
    private val authProvider: AuthProvider = NoOpAuthProvider()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // UDP Discovery components
    private var udpDiscoveryServer: UdpDiscoveryServer? = null
    private var udpDiscoveryClient: UdpDiscoveryClient? = null

    // Socket components
    private var tcpServer: TcpSocketServer? = null
    private var tcpClient: TcpSocketClient? = null

    // State
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerInfo>> = _discoveredPeers.asStateFlow()

    private val _messages = MutableSharedFlow<ProtoLanMessage>(replay = 0)
    val messages: SharedFlow<ProtoLanMessage> = _messages.asSharedFlow()

    private var isServerMode = false
    private var serverPort = 0
    private var currentPeerName: String? = null
    private var hasAuthenticatedInboundClient = false

    companion object {
        private const val TAG = "LanRepository"
    }

    private fun DiscoveredPeer.toPeerInfo(): PeerInfo {
        return PeerInfo(
            name = this.name,
            host = this.host,
            port = this.port
        )
    }

    // ===== LIFECYCLE =====

    /**
     * Close all resources and cancel coroutines.
     * Call this when the repository is no longer needed.
     */
    fun close() {
        stopServer()
        stopDiscovery()
        disconnect()
        scope.cancel()
    }

    // ===== SERVER MODE =====

    suspend fun startServer() = withContext(Dispatchers.IO) {
        // Stop any existing server first
        isServerMode = false
        tcpServer?.stop()
        tcpServer = null
        udpDiscoveryServer?.stop()
        udpDiscoveryServer = null

        isServerMode = true
        _connectionState.update { ConnectionState.Connecting }

        try {
            Log.d(TAG, "Creating TCP server...")
            val server = TcpSocketServer(port = 0, authProvider = authProvider)
            tcpServer = server
            serverPort = server.start()
            Log.d(TAG, "TCP Server started on port $serverPort")

            // Collect server messages via Flow
            scope.launch {
                server.messages.collect { bytes ->
                    try {
                        val message = ProtoLanMessage.parseFrom(bytes)
                        _messages.emit(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode server message bytes=${bytes.size}", e)
                    }
                }
            }

            // Observe connection state
            scope.launch {
                server.connectionState.collect { state ->
                    if (state is ConnectionState.Error) {
                        _connectionState.update { state }
                    }
                }
            }
            scope.launch {
                server.connectedPeers.collect { peers ->
                    if (hasAuthenticatedInboundClient && peers.isEmpty()) {
                        Log.d(TAG, "Inbound client disconnected")
                        hasAuthenticatedInboundClient = false
                        if (tcpClient == null) {
                            currentPeerName = null
                            _connectionState.update { ConnectionState.Idle }
                        }
                    }
                }
            }
            scope.launch {
                server.authenticatedPeers.collect { peer ->
                    hasAuthenticatedInboundClient = true
                    currentPeerName = peer.name
                    if (_connectionState.value is ConnectionState.Connected) {
                        return@collect
                    }

                    stopDiscovery()
                    _connectionState.update { ConnectionState.Connected(peer.name, isServer = true) }
                }
            }

            // Start UDP discovery broadcasting
            Log.d(TAG, "Creating UDP Discovery Server for port $serverPort...")
            val discoveryServer = UdpDiscoveryServer(context, serverPort)
            udpDiscoveryServer = discoveryServer
            Log.d(TAG, "Starting UDP Discovery Server...")
            discoveryServer.start()
            Log.d(TAG, "UDP Discovery broadcasting on port $serverPort")

            _connectionState.update { ConnectionState.Idle }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            _connectionState.update { ConnectionState.Error("Server failed: ${e.message}") }
            stopServer()
        }
    }

    fun stopServer() {
        Log.d(TAG, "Stopping server on port $serverPort")
        tcpServer?.stop()
        tcpServer = null
        udpDiscoveryServer?.stop()
        udpDiscoveryServer = null
        hasAuthenticatedInboundClient = false
        isServerMode = false
        _connectionState.update { ConnectionState.Idle }
    }

    // ===== CLIENT MODE =====

    suspend fun startMatching() = withContext(Dispatchers.IO) {
        startServer()
        startDiscovery()
    }

    suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        _connectionState.update { ConnectionState.Discovering }
        Log.d(TAG, "Starting peer discovery")

        try {
            val ignoredPorts = if (serverPort > 0) setOf(serverPort) else emptySet()
            val discoveryClient = UdpDiscoveryClient(ignoredServicePorts = ignoredPorts)
            udpDiscoveryClient = discoveryClient

            scope.launch {
                discoveryClient.discoveredPeers.collect { peers ->
                    _discoveredPeers.update { peers.map { it.toPeerInfo() } }
                }
            }

            discoveryClient.start()
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            _connectionState.update { ConnectionState.Error("Discovery failed: ${e.message}") }
        }
    }

    fun stopDiscovery() {
        Log.d(TAG, "Stopping peer discovery")
        udpDiscoveryClient?.stop()
        udpDiscoveryClient = null
        _discoveredPeers.update { emptyList() }
        if (_connectionState.value == ConnectionState.Discovering) {
            _connectionState.update { ConnectionState.Idle }
        }
    }

    suspend fun connectToPeer(peer: PeerInfo) = withContext(Dispatchers.IO) {
        if (_connectionState.value is ConnectionState.Connected) {
            Log.d(TAG, "Skipping outbound connect; already connected")
            return@withContext
        }

        _connectionState.update { ConnectionState.Connecting }

        try {
            Log.d(TAG, "Connecting to ${peer.name} at ${peer.host}:${peer.port}")

            val client = TcpSocketClient()
            tcpClient = client
            client.connect(peer)

            val authResponse = authenticateClient(client)
            if (!authResponse.success) {
                Log.w(TAG, "Client auth failed: ${authResponse.message}")
                _connectionState.update { ConnectionState.Error("Auth failed: ${authResponse.message}") }
                disconnect()
                return@withContext
            }

            if (_connectionState.value is ConnectionState.Connected) {
                Log.d(TAG, "Outbound auth succeeded after inbound connection; keeping both channels")
                client.startReadLoop()
                observeClientState(client)
                collectClientMessages(client)
                return@withContext
            }

            currentPeerName = peer.name
            stopDiscovery()
            isServerMode = false
            client.startReadLoop()
            observeClientState(client)
            collectClientMessages(client)
            _connectionState.update { ConnectionState.Connected(peer.name, isServer = false) }
            Log.d(TAG, "connectToPeer: authenticated and connected")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.update { ConnectionState.Error("Connection failed: ${e.message}") }
            disconnect()
        }
    }

    private suspend fun authenticateClient(client: TcpSocketClient): AuthResponse {
        val channel = client.protobufChannel ?: run {
            Log.e(TAG, "authenticateClient: protobufChannel is null!")
            throw IllegalStateException("Not connected")
        }

        val credentials = authProvider.getCredentials()
        val authRequest = AuthRequest.newBuilder().apply {
            deviceName = android.os.Build.MODEL
            if (credentials != null) {
                this.credentials = com.google.protobuf.ByteString.copyFrom(credentials)
            }
        }.build()

        Log.d(TAG, "Sending client auth request")
        channel.sendAuthRequest(authRequest)

        val authResponse = channel.receiveAuthResponse()
        Log.d(TAG, "Received client auth response: success=${authResponse.success}")
        return authResponse
    }

    private fun observeClientState(client: TcpSocketClient) {
        scope.launch {
            client.connectionState.collect { state ->
                if (tcpClient !== client) {
                    return@collect
                }

                when (state) {
                    is ConnectionState.Idle -> {
                        tcpClient = null
                        if (!hasAuthenticatedInboundClient && _connectionState.value is ConnectionState.Connected) {
                            currentPeerName = null
                            _connectionState.update { ConnectionState.Idle }
                        }
                    }
                    is ConnectionState.Error -> {
                        tcpClient = null
                        if (!hasAuthenticatedInboundClient) {
                            currentPeerName = null
                            _connectionState.update { state }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectClientMessages(client: TcpSocketClient) {
        scope.launch {
            client.messages.collect { bytes ->
                try {
                    val message = ProtoLanMessage.parseFrom(bytes)
                    _messages.emit(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode client message bytes=${bytes.size}", e)
                }
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from ${currentPeerName ?: "unknown peer"}")
        tcpClient?.disconnect()
        tcpClient = null
        currentPeerName = null
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.update { ConnectionState.Idle }
        }
    }

    // ===== MESSAGING =====

    suspend fun sendMessage(payload: String) = withContext(Dispatchers.IO) {
        try {
            val message = ProtoLanMessage.newBuilder().apply {
                id = java.util.UUID.randomUUID().toString()
                timestamp = System.currentTimeMillis()
                this.payload = com.google.protobuf.ByteString.copyFromUtf8(payload)
            }.build()
            val messageBytes = message.toByteArray()

            sendMessageBytes(messageBytes)

            // Emit locally so sender sees their own message
            _messages.emit(message)
        } catch (e: Exception) {
            Log.e(TAG, "Send message failed: ${e.message}")
            throw e
        }
    }

    private suspend fun sendMessageBytes(messageBytes: ByteArray) {
        val client = tcpClient
        if (client != null) {
            try {
                client.send(messageBytes)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Outbound send failed; clearing client", e)
                if (tcpClient === client) {
                    tcpClient = null
                }
                if (!hasAuthenticatedInboundClient && !isServerMode) {
                    currentPeerName = null
                    _connectionState.update { ConnectionState.Idle }
                    throw e
                }
            }
        }

        if (hasAuthenticatedInboundClient || isServerMode) {
            try {
                tcpServer?.send(messageBytes) ?: throw IllegalStateException("Not connected")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Server send failed", e)
                hasAuthenticatedInboundClient = false
                if (tcpClient == null) {
                    currentPeerName = null
                    _connectionState.update { ConnectionState.Idle }
                }
                throw e
            }
        }

        throw IllegalStateException("Not connected")
    }
}
