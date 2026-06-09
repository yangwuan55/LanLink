package com.ymr.lancomm.data.socket

import android.util.Log
import com.ymr.lancomm.domain.auth.AuthProvider
import com.ymr.lancomm.domain.auth.AuthResult
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import com.ymr.lancomm.net.LanServer
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
import kotlinx.coroutines.flow.update
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP Socket Server that accepts multiple client connections.
 * Provides Flow-based APIs for connection state, peer list, and messages.
 */
class TcpSocketServer(
    private val port: Int = 0,
    private val authProvider: AuthProvider,
    private val connectionTimeoutMs: Long = 30_000,
    private val heartbeatIntervalMs: Long = 15_000
) : LanServer {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track connected clients by their socket - ALL access via synchronized(clientSocketsLock)
    private val clientSockets = mutableMapOf<String, Socket>()
    private val clientChannels = mutableMapOf<String, ProtobufChannel>()
    private val clientLastHeartbeat = mutableMapOf<String, Long>()
    private val clientSocketsLock = Any()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val connectedPeers: StateFlow<List<PeerInfo>> = _connectedPeers.asStateFlow()

    // Each frame carries its wire type tag alongside the payload bytes.
    private val _messages = MutableSharedFlow<Pair<Int, ByteArray>>(replay = 0)
    override val messages: SharedFlow<Pair<Int, ByteArray>> = _messages.asSharedFlow()

    private val _authenticatedPeers = MutableSharedFlow<PeerInfo>(replay = 0)
    override val authenticatedPeers: SharedFlow<PeerInfo> = _authenticatedPeers.asSharedFlow()

    private var actualPort: Int = 0
    private var heartbeatJob: Job? = null

    companion object {
        private const val TAG = "TcpSocketServer"
    }

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        try {
            val server = ServerSocket(port).apply {
                soTimeout = 0  // No timeout for accept, read uses channel timeout
                reuseAddress = true
            }
            serverSocket = server
            actualPort = server.localPort
            _connectionState.value = ConnectionState.Connected("server", true)
            Log.d(TAG, "Server started on port $actualPort")

            // Start heartbeat monitor
            startHeartbeatMonitor()

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

    private fun startHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                checkHeartbeats()
            }
        }
    }

    private fun checkHeartbeats() {
        val now = System.currentTimeMillis()
        val timedOutClients = mutableListOf<String>()
        synchronized(clientSocketsLock) {
            clientLastHeartbeat.forEach { (clientId, lastTime) ->
                if (now - lastTime > connectionTimeoutMs) {
                    timedOutClients.add(clientId)
                }
            }
        }
        timedOutClients.forEach { clientId ->
            Log.w(TAG, "Client $clientId heartbeat timeout, closing connection")
            disconnectClient(clientId)
        }
    }

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        while (isActive) {
            try {
                val server = serverSocket ?: break
                if (server.isClosed) break

                val clientSocket = server.accept()
                clientSocket.soTimeout = (connectionTimeoutMs * 2).toInt()
                val clientId = "${clientSocket.remoteSocketAddress}"
                Log.d(TAG, "Client connected from $clientId")

                // Store socket reference
                synchronized(clientSocketsLock) {
                    clientSockets[clientId] = clientSocket
                    clientLastHeartbeat[clientId] = System.currentTimeMillis()
                }

                // Add peer to connectedPeers
                val peer = PeerInfo(
                    name = clientId,
                    host = clientSocket.inetAddress.hostAddress ?: "",
                    port = clientSocket.port
                )
                _connectedPeers.update { it + peer }

                // Handle client in separate coroutine
                scope.launch {
                    handleClient(clientSocket, peer, clientId)
                }

            } catch (e: SocketException) {
                if (e.message != "Socket closed") {
                    Log.e(TAG, "Accept error", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Accept loop unexpected error", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket, peer: PeerInfo, clientId: String) = withContext(Dispatchers.IO) {
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

            // Store channel for sending
            synchronized(clientSocketsLock) {
                clientChannels[clientId] = protobufChannel
                clientLastHeartbeat[clientId] = System.currentTimeMillis()
            }
            Log.d(TAG, "Authentication successful for $clientId")
            _authenticatedPeers.emit(peer)

            // Continue with normal message handling using ProtobufChannel
            while (isActive && !socket.isClosed && socket.isConnected) {
                try {
                    val lanMessage = protobufChannel.receiveLanMessage()

                    // Update heartbeat on any message received
                    synchronized(clientSocketsLock) {
                        clientLastHeartbeat[clientId] = System.currentTimeMillis()
                    }

                    // Skip heartbeat pings
                    if (lanMessage.id.startsWith("heartbeat-")) {
                        Log.d(TAG, "Heartbeat ping from $clientId")
                        protobufChannel.send(lanMessage)
                        continue
                    }

                    val bytes = lanMessage.payload.toByteArray()
                    Log.d(TAG, "Received ${bytes.size} bytes from $clientId (type=${lanMessage.type})")
                    _messages.emit(lanMessage.type to bytes)
                } catch (e: SocketException) {
                    Log.d(TAG, "Read error from $clientId", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error for $clientId", e)
        } finally {
            disconnectClient(clientId)
        }
    }

    private fun disconnectClient(clientId: String) {
        synchronized(clientSocketsLock) {
            clientSockets.remove(clientId)?.let { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
            clientChannels.remove(clientId)
            clientLastHeartbeat.remove(clientId)
        }
        _connectedPeers.update { peers -> peers.filter { "${it.host}:${it.port}" != clientId } }
        Log.d(TAG, "Client $clientId disconnected and cleaned up")
    }

    /**
     * Send message to a specific client by ID.
     */
    suspend fun sendToClient(clientId: String, type: Int, message: ByteArray) = withContext(Dispatchers.IO) {
        val channel = synchronized(clientSocketsLock) {
            clientChannels[clientId]
        }

        channel?.let { ch ->
            try {
                val lanMessage = LanMessage.newBuilder()
                    .setId(java.util.UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .setPayload(ByteString.copyFrom(message))
                    .setType(type)
                    .build()
                ch.send(lanMessage)
                // Update heartbeat on send
                synchronized(clientSocketsLock) {
                    clientLastHeartbeat[clientId] = System.currentTimeMillis()
                }
                Log.d(TAG, "Sent ${message.size} bytes to $clientId")
            } catch (e: Exception) {
                Log.e(TAG, "Send error to $clientId", e)
                disconnectClient(clientId)
            }
        } ?: run {
            Log.w(TAG, "Client channel not found for $clientId")
        }
    }

    /**
     * Broadcast message to all connected clients.
     */
    suspend fun broadcast(type: Int, message: ByteArray) = withContext(Dispatchers.IO) {
        val clientsToSend: Map<String, ProtobufChannel>
        synchronized(clientSocketsLock) {
            clientsToSend = clientChannels.toMap()
        }

        if (clientsToSend.isEmpty()) {
            throw IllegalStateException("No clients connected")
        }

        val lanMessage = LanMessage.newBuilder()
            .setId(java.util.UUID.randomUUID().toString())
            .setTimestamp(System.currentTimeMillis())
            .setPayload(ByteString.copyFrom(message))
            .setType(type)
            .build()

        val now = System.currentTimeMillis()
        var sentCount = 0
        val failedClients = mutableListOf<String>()

        clientsToSend.forEach { (clientId, channel) ->
            try {
                channel.send(lanMessage)
                sentCount++
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast send error to $clientId", e)
                failedClients.add(clientId)
            }
        }

        // Update heartbeats for all sent clients
        synchronized(clientSocketsLock) {
            clientsToSend.keys.forEach { clientId ->
                clientLastHeartbeat[clientId] = now
            }
        }

        // Clean up failed clients
        failedClients.forEach { clientId ->
            disconnectClient(clientId)
        }

        if (sentCount == 0) {
            throw IllegalStateException("No clients accepted broadcast")
        }

        Log.d(TAG, "Broadcast to $sentCount clients (${failedClients.size} failed)")
    }

    /**
     * Send message to first available client (legacy behavior for backward compatibility).
     * Prefer broadcast() for sending to all clients.
     */
    override suspend fun send(type: Int, message: ByteArray): Unit {
        broadcast(type, message)
    }

    override fun stop() {
        heartbeatJob?.cancel()
        scope.cancel()

        // Close all client sockets
        synchronized(clientSocketsLock) {
            clientSockets.values.forEach { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
            clientSockets.clear()
            clientChannels.clear()
            clientLastHeartbeat.clear()
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
