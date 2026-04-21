package com.ymr.lancomm.data.socket

import android.util.Log
import com.ymr.lancomm.domain.auth.AuthProvider
import com.ymr.lancomm.domain.auth.AuthResult
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import com.google.protobuf.ByteString
import com.ymr.lancomm.proto.AuthResponse
import com.ymr.lancomm.proto.LanMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP Socket Server that accepts multiple client connections.
 * Provides Flow-based APIs for connection state, peer list, and messages.
 */
class TcpSocketServer(
    private val port: Int = 0,
    private val authProvider: AuthProvider
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track connected clients by their socket
    private val clientSockets = mutableMapOf<String, Socket>()
    private val clientChannels = mutableMapOf<String, ProtobufChannel>()
    private val clientSocketsLock = Any()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val connectedPeers: StateFlow<List<PeerInfo>> = _connectedPeers.asStateFlow()

    private val _messages = MutableSharedFlow<ByteArray>(replay = 0)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private var actualPort: Int = 0

    companion object {
        private const val TAG = "TcpSocketServer"
    }

    suspend fun start(): Int = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(port).apply {
                soTimeout = 0  // No timeout
                reuseAddress = true
            }
            actualPort = serverSocket!!.localPort
            _connectionState.value = ConnectionState.Connected("server", true)
            Log.d(TAG, "Server started on port $actualPort")

            // Start accept loop
            scope.launch {
                acceptLoop()
            }

            actualPort
        } catch (e: Exception) {
            Log.e(TAG, "Server start error", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
            try {
                val clientSocket = serverSocket!!.accept()
                val clientId = "${clientSocket.remoteSocketAddress}"
                Log.d(TAG, "Client connected from $clientId")

                // Store socket reference
                synchronized(clientSocketsLock) {
                    clientSockets[clientId] = clientSocket
                }

                // Add peer to connectedPeers
                val peer = PeerInfo(
                    name = clientId,
                    host = clientSocket.inetAddress,
                    port = clientSocket.port
                )
                _connectedPeers.value = _connectedPeers.value + peer

                // Handle client in separate coroutine
                scope.launch {
                    handleClient(clientSocket, peer)
                }

            } catch (e: SocketException) {
                if (e.message != "Socket closed") {
                    Log.e(TAG, "Accept error", e)
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket, peer: PeerInfo) = withContext(Dispatchers.IO) {
        val clientId = "${socket.remoteSocketAddress}"
        try {
            val protobufChannel = ProtobufChannel(socket.getInputStream(), socket.getOutputStream())

            // Auth handshake - receive AuthRequest
            val authRequest = protobufChannel.receiveAuthRequest()
            Log.d(TAG, "Received AuthRequest from $clientId: deviceName=${authRequest.deviceName}")

// Authenticate
            val authResult = authProvider.authenticate(
                authRequest.deviceName,
                authRequest.credentials.toByteArray()
            )

            // Send AuthResponse
            val authResponse = when (authResult) {
                is AuthResult.Success -> AuthResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("OK")
                    .build()
                is AuthResult.Failure -> AuthResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage(authResult.message)
                    .build()
            }
            protobufChannel.sendAuthResponse(authResponse)

            // If authentication failed, close connection immediately
            if (authResult is AuthResult.Failure) {
                Log.w(TAG, "Authentication failed for $clientId: ${authResult.message}")
                socket.close()
                return@withContext
            }

            Log.d(TAG, "Authentication successful for $clientId")

            // Store channel for sending
            synchronized(clientSocketsLock) {
                clientChannels[clientId] = protobufChannel
            }

            // Continue with normal message handling using ProtobufChannel
            while (isActive && !socket.isClosed && socket.isConnected) {
                try {
                    val lanMessage = protobufChannel.receiveLanMessage()
                    val bytes = lanMessage.payload.toByteArray()
                    Log.d(TAG, "Received ${bytes.size} bytes from $clientId")
                    _messages.emit(bytes)
                } catch (e: SocketException) {
                    Log.d(TAG, "Read error from $clientId", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error for $clientId", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
            synchronized(clientSocketsLock) {
                clientSockets.remove(clientId)
                clientChannels.remove(clientId)
            }
            _connectedPeers.value = _connectedPeers.value.filter { it.host != peer.host || it.port != peer.port }
            Log.d(TAG, "Client $clientId disconnected and cleaned up")
        }
    }

    suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        val currentPeers = _connectedPeers.value
        if (currentPeers.isEmpty()) {
            Log.w(TAG, "No clients connected, cannot send")
            return@withContext
        }

        val peer = currentPeers.first()
        val clientId = peer.name

        val channel = synchronized(clientSocketsLock) {
            clientChannels[clientId]
        }

        channel?.let { ch ->
            try {
                val lanMessage = LanMessage.newBuilder()
                    .setId(java.util.UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .setPayload(com.google.protobuf.ByteString.copyFrom(message))
                    .build()
                ch.send(lanMessage)
                Log.d(TAG, "Sent ${message.size} bytes to ${peer.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                throw e
            }
        } ?: run {
            Log.w(TAG, "Client channel not found for ${peer.name}")
        }
    }

    fun stop() {
        scope.cancel()
        
        // Close all client sockets
        synchronized(clientSocketsLock) {
            clientSockets.values.forEach { socket ->
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            clientSockets.clear()
        }
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        _connectionState.value = ConnectionState.Idle
        _connectedPeers.value = emptyList()
        Log.d(TAG, "Server stopped")
    }
}
