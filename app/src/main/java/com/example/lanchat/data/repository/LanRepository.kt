package com.example.lanchat.data.repository

import android.content.Context
import android.util.Log
import com.example.lanchat.data.auth.NoOpAuthProvider
import com.example.lanchat.data.discovery.UdpDiscoveryServer
import com.example.lanchat.data.discovery.UdpDiscoveryClient
import com.example.lanchat.data.discovery.DiscoveredPeer
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
import java.net.InetAddress
import com.example.lanchat.proto.AuthResponse
import java.io.InputStream
import java.io.OutputStream
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
    }

    private fun DiscoveredPeer.toPeerInfo(): PeerInfo {
        return PeerInfo(
            name = this.name,
            host = InetAddress.getByName(this.host),
            port = this.port
        )
    }

    // ===== SERVER MODE =====

    suspend fun startServer() = withContext(Dispatchers.IO) {
        // Stop any existing server first (but don't cancel scope - we need it for new coroutines)
        isServerMode = false
        tcpServer?.stop()
        tcpServer = null
        udpDiscoveryServer?.stop()
        udpDiscoveryServer = null
        protobufChannel = null

        isServerMode = true
        _connectionState.value = ConnectionState.Connecting

        try {
            // Start TCP server first
            Log.d(TAG, "Creating TCP server...")
            val serverCallback = createServerCallback()
            tcpServer = TcpSocketServer(port = 0, callback = serverCallback)
            serverPort = tcpServer!!.start()
            Log.d(TAG, "TCP Server started on port $serverPort")

            // Start UDP discovery broadcasting
            Log.d(TAG, "Creating UDP Discovery Server for port $serverPort...")
            udpDiscoveryServer = UdpDiscoveryServer(context, serverPort)
            Log.d(TAG, "Starting UDP Discovery Server...")
            udpDiscoveryServer!!.start()
            Log.d(TAG, "UDP Discovery broadcasting on port $serverPort")

            _connectionState.value = ConnectionState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            e.printStackTrace()
            _connectionState.value = ConnectionState.Error("Server failed: ${e.message}")
            stopServer()
        }
    }

    private fun createServerCallback() = object : SocketCallback {
        override fun onConnected(inputStream: InputStream, outputStream: OutputStream) {
            Log.d(TAG, "Client connected to server")
            protobufChannel = ProtobufChannel(inputStream, outputStream)
            _connectionState.value = ConnectionState.Connected("[Client]", isServer = true)
            scope.launch { handleServerSession() }
        }

        override fun onDisconnected() {
            Log.d(TAG, "Client disconnected from server")
            protobufChannel = null
            _connectionState.value = ConnectionState.Idle
        }

        override fun onMessageReceived(message: ByteArray) {
            Log.d(TAG, "Server received message: ${message.size} bytes")
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "Server socket error", error)
            _connectionState.value = ConnectionState.Error(error.message ?: "Unknown error")
        }
    }

    private suspend fun handleServerSession() {
        Log.d(TAG, "handleServerSession: starting, channel=${protobufChannel}")
        Log.d(TAG, ">>> HANDLE_SERVER_SESSION_START channel=${protobufChannel != null}")
        val channel = protobufChannel ?: run {
            Log.e(TAG, "handleServerSession: channel is null!")
            return
        }
        Log.d(TAG, "handleServerSession: channel ready")
        Log.d(TAG, ">>> SERVER_SESSION_READY")
        try {
            var authDone = false
            while (currentCoroutineContext().isActive && !authDone) {
                try {
                    Log.d(TAG, "handleServerSession: waiting for auth request...")
                    Log.d(TAG, ">>> SERVER_WAITING_FOR_AUTH")
                    val request = channel.receiveAuthRequest()
                    Log.d(TAG, ">>> SERVER_RECEIVED_AUTH from=${request.deviceName}")
                    val result = authProvider.authenticate(request.deviceName, request.credentials.toByteArray())
                    val response = AuthResponse.newBuilder().apply {
                        success = result is AuthResult.Success
                        message = when (result) {
                            is AuthResult.Success -> "OK"
                            is AuthResult.Failure -> result.message
                        }
                    }.build()
                    channel.sendAuthResponse(response)
                    Log.d(TAG, ">>> SERVER_SENT_AUTH_RESPONSE success=${response.success}")
                    authDone = true
                } catch (e: Exception) {
                    Log.e(TAG, "Auth exchange error, exiting: ${e.message}")
                    return
                }
            }
            while (currentCoroutineContext().isActive) {
                try {
                    val message = channel.receiveLanMessage()
                    Log.d(TAG, "Server received message: ${message.payload}")
                    _messages.emit(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Message receive error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server session error: ${e.message}")
        }
    }

    fun stopServer() {
        Log.d(TAG, "Stopping server on port $serverPort")
        tcpServer?.stop()
        tcpServer = null
        udpDiscoveryServer?.stop()
        udpDiscoveryServer = null
        protobufChannel = null
        isServerMode = false
        _connectionState.value = ConnectionState.Idle
    }

    // ===== CLIENT MODE =====

    suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        isServerMode = false
        _connectionState.value = ConnectionState.Discovering
        Log.d(TAG, "Starting peer discovery")

        try {
            udpDiscoveryClient = UdpDiscoveryClient()

            scope.launch {
                udpDiscoveryClient!!.discoveredPeers.collect { peers ->
                    _discoveredPeers.value = peers.map { it.toPeerInfo() }
                }
            }

            udpDiscoveryClient!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
            _connectionState.value = ConnectionState.Error("Discovery failed: ${e.message}")
        }
    }

    fun stopDiscovery() {
        Log.d(TAG, "Stopping peer discovery")
        udpDiscoveryClient?.stop()
        udpDiscoveryClient = null
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

            currentPeerName = peer.name
            _connectionState.value = ConnectionState.Connected(peer.name, isServer = false)
            Log.d(TAG, "connectToPeer: launching handleClientSession")

            scope.launch { handleClientSession() }
            Log.d(TAG, "connectToPeer: handleClientSession launched")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
            disconnect()
        }
    }

    private fun createClientCallback() = object : SocketCallback {
        override fun onConnected(inputStream: InputStream, outputStream: OutputStream) {
            Log.d(TAG, "Connected to server")
            protobufChannel = ProtobufChannel(inputStream, outputStream)
        }

        override fun onDisconnected() {
            Log.d(TAG, "Disconnected from server")
            protobufChannel = null
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

    private suspend fun handleClientSession() {
        Log.d(TAG, "handleClientSession: starting, channel=${protobufChannel}")
        Log.d(TAG, ">>> HANDLE_CLIENT_SESSION_START channel=${protobufChannel != null}")
        val channel = protobufChannel ?: run {
            Log.e(TAG, "handleClientSession: channel is null!")
            return
        }
        Log.d(TAG, "handleClientSession: channel ready")
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

            val response = channel.receiveAuthResponse()
            Log.d(TAG, ">>> CLIENT_RECEIVED_AUTH_RESPONSE success=${response.success}")
            if (response.success) {
                Log.d(TAG, ">>> CLIENT_AUTH_SUCCESS")
            } else {
                Log.w(TAG, "Authentication failed: ${response.message}")
                return
            }

            while (currentCoroutineContext().isActive) {
                try {
                    val message = channel.receiveLanMessage()
                    Log.d(TAG, "Client received message: ${message.payload}")
                    _messages.emit(message)
                } catch (e: Exception) {
                    Log.d(TAG, "Message receive error", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client session error", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from ${currentPeerName ?: "unknown peer"}")
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
            Log.d(TAG, "sendMessage: channel=${protobufChannel}, messageId=${message.id}")
            val sent = protobufChannel?.send(message) ?: throw IllegalStateException("Cannot send: protobufChannel is null")
            Log.d(TAG, ">>> MESSAGE_SENT_BY_CLIENT payload='$payload'")
        } catch (e: Exception) {
            Log.e(TAG, ">>> SEND_MESSAGE_FAILED: ${e.message}")
            throw e
        }
    }
}