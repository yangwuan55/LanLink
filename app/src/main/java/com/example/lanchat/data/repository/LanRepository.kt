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
        scope.cancel()
        stopServer()
        stopDiscovery()
        disconnect()
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
            tcpServer = TcpSocketServer(port = 0, authProvider = authProvider)
            serverPort = tcpServer.start()
            Log.d(TAG, "TCP Server started on port $serverPort")

            // Collect server messages via Flow
            scope.launch {
                tcpServer.messages.collect { bytes ->
                    try {
                        val message = ProtoLanMessage.parseFrom(bytes)
                        Log.d(TAG, "Server received message: ${message.payload}")
                        _messages.emit(message)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode message", e)
                    }
                }
            }

            // Observe connection state
            scope.launch {
                tcpServer.connectionState.collect { state ->
                    when (state) {
                        is ConnectionState.Connected -> {
                            // Server mode connected
                        }
                        is ConnectionState.Idle -> {
                            // Server idle
                        }
                        is ConnectionState.Error -> {
                            _connectionState.update { state }
                        }
                        else -> {}
                    }
                }
            }

            // Start UDP discovery broadcasting
            Log.d(TAG, "Creating UDP Discovery Server for port $serverPort...")
            udpDiscoveryServer = UdpDiscoveryServer(context, serverPort)
            Log.d(TAG, "Starting UDP Discovery Server...")
            udpDiscoveryServer.start()
            Log.d(TAG, "UDP Discovery broadcasting on port $serverPort")

            _connectionState.update { ConnectionState.Idle }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            e.printStackTrace()
            _connectionState.update { ConnectionState.Error("Server failed: ${e.message}") }
            stopServer()
        }
    }

    private suspend fun handleServerSession() {
        Log.d(TAG, "handleServerSession: starting")
        val server = tcpServer ?: run {
            Log.e(TAG, "handleServerSession: tcpServer is null!")
            return
        }
        Log.d(TAG, "handleServerSession: server ready")
    }

    fun stopServer() {
        Log.d(TAG, "Stopping server on port $serverPort")
        tcpServer?.stop()
        tcpServer = null
        udpDiscoveryServer?.stop()
        udpDiscoveryServer = null
        isServerMode = false
        _connectionState.update { ConnectionState.Idle }
    }

    // ===== CLIENT MODE =====

    suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        isServerMode = false
        _connectionState.update { ConnectionState.Discovering }
        Log.d(TAG, "Starting peer discovery")

        try {
            udpDiscoveryClient = UdpDiscoveryClient()

            scope.launch {
                udpDiscoveryClient!!.discoveredPeers.collect { peers ->
                    _discoveredPeers.update { peers.map { it.toPeerInfo() } }
                }
            }

            udpDiscoveryClient!!.start()
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
        _connectionState.update { ConnectionState.Connecting }

        try {
            Log.d(TAG, "Connecting to ${peer.name} at ${peer.host}:${peer.port}")

            tcpClient = TcpSocketClient()
            tcpClient.connect(peer)

            currentPeerName = peer.name
            _connectionState.update { ConnectionState.Connected(peer.name, isServer = false) }
            Log.d(TAG, "connectToPeer: launching handleClientSession")

            scope.launch { handleClientSession() }
            Log.d(TAG, "connectToPeer: handleClientSession launched")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.update { ConnectionState.Error("Connection failed: ${e.message}") }
            disconnect()
        }
    }

    private suspend fun handleClientSession() {
        Log.d(TAG, "handleClientSession: starting")
        val client = tcpClient ?: run {
            Log.e(TAG, "handleClientSession: tcpClient is null!")
            return
        }
        val channel = client.protobufChannel ?: run {
            Log.e(TAG, "handleClientSession: protobufChannel is null!")
            return
        }
        Log.d(TAG, "handleClientSession: client ready")
        Log.d(TAG, ">>> CLIENT_SESSION_READY")

        try {
            val credentials = authProvider.getCredentials()
            val authRequest = AuthRequest.newBuilder().apply {
                deviceName = android.os.Build.MODEL
                if (credentials != null) {
                    this.credentials = com.google.protobuf.ByteString.copyFrom(credentials)
                }
            }.build()

            Log.d(TAG, ">>> CLIENT_SENDING_AUTH_REQUEST")
            channel.sendAuthRequest(authRequest)
            Log.d(TAG, ">>> CLIENT_SENT_AUTH_REQUEST")

            val authResponse = channel.receiveAuthResponse()
            Log.d(TAG, ">>> CLIENT_RECEIVED_AUTH_RESPONSE: success=${authResponse.success}")

            if (authResponse.success) {
                Log.d(TAG, ">>> CLIENT_AUTH_SUCCESS")
            } else {
                Log.w(TAG, ">>> CLIENT_AUTH_FAILED: ${authResponse.message}")
                disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client session error", e)
            disconnect()
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
            Log.d(TAG, "sendMessage: messageId=${message.id}")

            if (isServerMode) {
                tcpServer?.send(messageBytes)
            } else {
                tcpClient?.send(messageBytes)
            }
            Log.d(TAG, ">>> MESSAGE_SENT payload='$payload'")
        } catch (e: Exception) {
            Log.e(TAG, ">>> SEND_MESSAGE_FAILED: ${e.message}")
            throw e
        }
    }
}