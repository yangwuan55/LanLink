package com.example.lanchat.data.repository

import android.content.Context
import android.util.Log
import com.example.lanchat.data.auth.NoOpAuthProvider
import com.example.lanchat.data.nsd.NsdAdvertiser
import com.example.lanchat.data.nsd.NsdDiscoverer
import com.example.lanchat.data.socket.ProtobufChannel
import com.example.lanchat.data.socket.SocketCallback
import com.example.lanchat.data.socket.TcpSocketClient
import com.example.lanchat.data.socket.TcpSocketServer
import com.example.lanchat.domain.auth.AuthProvider
import com.example.lanchat.domain.auth.AuthResult
import com.example.lanchat.domain.model.ConnectionState
import com.example.lanchat.proto.LanMessage as ProtoLanMessage
import com.example.lanchat.domain.model.PeerInfo
import com.example.lanchat.proto.AuthRequest
import com.example.lanchat.proto.AuthResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LanRepository(
    private val context: Context,
    private val authProvider: AuthProvider = NoOpAuthProvider()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // NSD components
    private var nsdAdvertiser: NsdAdvertiser? = null
    private var nsdDiscoverer: NsdDiscoverer? = null

    // Socket components
    private var tcpServer: TcpSocketServer? = null
    private var tcpClient: TcpSocketClient? = null
    private var protobufChannel: ProtobufChannel? = null

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
        private const val SERVICE_NAME_PREFIX = "LanChat_"
    }

    // ===== SERVER MODE =====

    suspend fun startServer() = withContext(Dispatchers.IO) {
        isServerMode = true
        _connectionState.value = ConnectionState.Connecting

        try {
            // Start TCP server first
            val serverCallback = createServerCallback()
            tcpServer = TcpSocketServer(port = 0, callback = serverCallback)
            serverPort = tcpServer!!.start()
            Log.d(TAG, "TCP Server started on port $serverPort")

            // Start NSD advertising
            nsdAdvertiser = NsdAdvertiser(context)
            val serviceName = SERVICE_NAME_PREFIX + android.os.Build.MODEL
            nsdAdvertiser!!.registerService(serviceName, serverPort)
            Log.d(TAG, "NSD advertising: $serviceName on port $serverPort")

            _connectionState.value = ConnectionState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            _connectionState.value = ConnectionState.Error("Server failed: ${e.message}")
            stopServer()
        }
    }

    private fun createServerCallback() = object : SocketCallback {
        override fun onConnected() {
            Log.d(TAG, "Client connected to server")
            _connectionState.value = ConnectionState.Connected("[Client]", isServer = true)
        }

        override fun onDisconnected() {
            Log.d(TAG, "Client disconnected from server")
            _connectionState.value = ConnectionState.Idle
        }

        override fun onMessageReceived(message: ByteArray) {
            Log.d(TAG, "Server received message: ${message.size} bytes")
            // Handle incoming message - for now just log
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Server socket error", error)
            _connectionState.value = ConnectionState.Error(error.message ?: "Unknown error")
        }
    }

    fun stopServer() {
        scope.cancel()
        tcpServer?.stop()
        tcpServer = null
        nsdAdvertiser?.unregisterService()
        nsdAdvertiser = null
        protobufChannel = null
        _connectionState.value = ConnectionState.Idle
    }

    // ===== CLIENT MODE =====

    suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        isServerMode = false
        _connectionState.value = ConnectionState.Discovering

        try {
            nsdDiscoverer = NsdDiscoverer(context)

            // Collect discovered peers
            scope.launch {
                nsdDiscoverer!!.discoveredPeers.collect { peers ->
                    _discoveredPeers.value = peers
                }
            }

            // Start discovery
            nsdDiscoverer!!.discoverServices()
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            _connectionState.value = ConnectionState.Error("Discovery failed: ${e.message}")
        }
    }

    fun stopDiscovery() {
        nsdDiscoverer?.stopDiscovery()
        nsdDiscoverer = null
        _discoveredPeers.value = emptyList()
        if (_connectionState.value == ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    suspend fun connectToPeer(peer: PeerInfo) = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting

        try {
            Log.d(TAG, "Connecting to ${peer.name} at ${peer.host}:${peer.port}")

            val clientCallback = createClientCallback()
            tcpClient = TcpSocketClient(callback = clientCallback)
            tcpClient!!.connect(peer.host.hostAddress ?: peer.host.toString(), peer.port)

            // Connection successful
            currentPeerName = peer.name
            _connectionState.value = ConnectionState.Connected(peer.name, isServer = false)

            // Perform authentication
            authenticate()
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
            disconnect()
        }
    }

    private fun createClientCallback() = object : SocketCallback {
        override fun onConnected() {
            Log.d(TAG, "Connected to server")
        }

        override fun onDisconnected() {
            Log.d(TAG, "Disconnected from server")
            _connectionState.value = ConnectionState.Idle
        }

        override fun onMessageReceived(message: ByteArray) {
            Log.d(TAG, "Client received message: ${message.size} bytes")
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Client socket error", error)
            _connectionState.value = ConnectionState.Error(error.message ?: "Unknown error")
        }
    }

    private suspend fun authenticate() {
        try {
            val credentials = authProvider.getCredentials()
            val authRequest = AuthRequest.newBuilder().apply {
                deviceName = android.os.Build.MODEL
                if (credentials != null) {
                    this.credentials = com.google.protobuf.ByteString.copyFrom(credentials)
                }
            }.build()

            // Send auth request
            protobufChannel?.sendAuthRequest(authRequest)

            // Wait for response
            val response = protobufChannel?.receiveAuthResponse()
            if (response?.success == true) {
                Log.d(TAG, "Authentication successful")
            } else {
                Log.w(TAG, "Authentication failed: ${response?.message}")
                disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
        }
    }

    fun disconnect() {
        tcpClient?.disconnect()
        tcpClient = null
        protobufChannel = null
        currentPeerName = null
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    // ===== MESSAGING =====

    suspend fun sendMessage(payload: String) = withContext(Dispatchers.IO) {
        try {
            val message = ProtoLanMessage.newBuilder().apply {
                id = java.util.UUID.randomUUID().toString()
                timestamp = System.currentTimeMillis()
                this.payload = payload
            }.build()
            protobufChannel?.send(message)
            Log.d(TAG, "Sent message: $payload")
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            throw e
        }
    }
}