package com.ymr.lancomm.data.socket

import com.ymr.lancomm.data.proto.AuthResponse
import com.ymr.lancomm.data.proto.LanMessage
import com.ymr.lancomm.data.proto.decodeAuthRequest
import com.ymr.lancomm.data.proto.decodeLanMessage
import com.ymr.lancomm.data.proto.encode
import com.ymr.lancomm.data.proto.readDelimited
import com.ymr.lancomm.data.proto.writeDelimited
import com.ymr.lancomm.domain.auth.AuthProvider
import com.ymr.lancomm.domain.auth.AuthResult
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import com.ymr.lancomm.net.LanServer
import com.ymr.lancomm.platform.ioDispatcher
import com.ymr.lancomm.platform.logger
import com.ymr.lancomm.platform.nowMillis
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * TCP Socket Server that accepts multiple client connections.
 * Provides Flow-based APIs for connection state, peer list, and messages.
 *
 * Ktor (commonMain) reimplementation of the former androidMain java.net server.
 * Framing is delegated to [writeDelimited]/[readDelimited]; ProtobufChannel is gone.
 */
class TcpSocketServer(
    private val port: Int = 0,
    private val authProvider: AuthProvider,
    private val connectionTimeoutMs: Long = 30_000,
    private val heartbeatIntervalMs: Long = 15_000
) : LanServer {
    private var selector: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    // Track connected clients keyed by a single stable clientId (the ktor
    // remoteAddress string). ALL map access is guarded by [clientsMutex].
    private val clientSockets = mutableMapOf<String, Socket>()
    private val clientWriters = mutableMapOf<String, ByteWriteChannel>()
    private val clientPeers = mutableMapOf<String, PeerInfo>()
    private val clientLastHeartbeat = mutableMapOf<String, Long>()
    private val clientsMutex = Mutex()

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

    override suspend fun start(): Int = withContext(ioDispatcher) {
        try {
            val newSelector = SelectorManager(ioDispatcher)
            val server = aSocket(newSelector).tcp().bind(InetSocketAddress("0.0.0.0", port))
            selector = newSelector
            serverSocket = server
            actualPort = (server.localAddress as InetSocketAddress).port
            _connectionState.value = ConnectionState.Connected("server", true)
            logger.d(TAG, "Server started on port $actualPort")

            // Start heartbeat monitor
            startHeartbeatMonitor()

            // Start accept loop
            scope.launch {
                acceptLoop()
            }

            actualPort
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Server start error", e)
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

    private suspend fun checkHeartbeats() {
        val now = nowMillis()
        val timedOutClients = mutableListOf<String>()
        clientsMutex.withLock {
            clientLastHeartbeat.forEach { (clientId, lastTime) ->
                if (now - lastTime > connectionTimeoutMs) {
                    timedOutClients.add(clientId)
                }
            }
        }
        timedOutClients.forEach { clientId ->
            logger.w(TAG, "Client $clientId heartbeat timeout, closing connection")
            disconnectClient(clientId)
        }
    }

    private suspend fun acceptLoop() = withContext(ioDispatcher) {
        while (isActive) {
            try {
                val server = serverSocket ?: break

                val clientSocket = server.accept()
                val clientId = clientSocket.remoteAddress.toString()
                val remote = clientSocket.remoteAddress as InetSocketAddress
                logger.d(TAG, "Client connected from $clientId")

                val peer = PeerInfo(
                    name = clientId,
                    host = remote.hostname,
                    port = remote.port
                )

                // Store socket reference + peer under the single clientId key.
                clientsMutex.withLock {
                    clientSockets[clientId] = clientSocket
                    clientPeers[clientId] = peer
                    clientLastHeartbeat[clientId] = nowMillis()
                }
                _connectedPeers.update { it + peer }

                // Handle client in separate coroutine
                scope.launch {
                    handleClient(clientSocket, peer, clientId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.w(TAG, "Accept stopped (socket closed)", e)
                break
            } catch (e: Exception) {
                logger.e(TAG, "Accept loop unexpected error", e)
            }
        }
    }

    private suspend fun handleClient(socket: Socket, peer: PeerInfo, clientId: String) = withContext(ioDispatcher) {
        try {
            val read = socket.openReadChannel()
            val write = socket.openWriteChannel()

            // Auth handshake - receive AuthRequest
            val authRequest = decodeAuthRequest(read.readDelimited())
            logger.d(TAG, "Received AuthRequest from $clientId: deviceName=${authRequest.deviceName}")

            // Authenticate
            val authResult = authProvider.authenticate(
                authRequest.deviceName,
                authRequest.credentials
            )

            // Send AuthResponse
            val authResponse = when (authResult) {
                is AuthResult.Success -> AuthResponse(success = true, message = "OK")
                is AuthResult.Failure -> AuthResponse(success = false, message = authResult.message)
            }
            write.writeDelimited(authResponse.encode())

            // If authentication failed, close connection immediately
            if (authResult is AuthResult.Failure) {
                logger.w(TAG, "Authentication failed for $clientId: ${authResult.message}")
                socket.close()
                return@withContext
            }

            // Store writer for sending
            clientsMutex.withLock {
                clientWriters[clientId] = write
                clientLastHeartbeat[clientId] = nowMillis()
            }
            logger.d(TAG, "Authentication successful for $clientId")
            _authenticatedPeers.emit(peer)

            // Continue with normal message handling
            while (isActive) {
                val lanMessage = try {
                    decodeLanMessage(read.readDelimited())
                } catch (e: IOException) {
                    // Covers EOF / peer-closed reads (kotlinx.io.EOFException) and other I/O errors.
                    logger.w(TAG, "Read error from $clientId, connection closed", e)
                    break
                }

                // Update heartbeat on any message received
                clientsMutex.withLock {
                    clientLastHeartbeat[clientId] = nowMillis()
                }

                // Skip heartbeat pings (echo back to client)
                if (lanMessage.id.startsWith("heartbeat-")) {
                    logger.d(TAG, "Heartbeat ping from $clientId")
                    write.writeDelimited(lanMessage.encode())
                    continue
                }

                val bytes = lanMessage.payload
                logger.d(TAG, "Received ${bytes.size} bytes from $clientId (type=${lanMessage.type})")
                _messages.emit(lanMessage.type to bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Client handler error for $clientId", e)
        } finally {
            disconnectClient(clientId)
        }
    }

    private suspend fun disconnectClient(clientId: String) {
        val peer: PeerInfo?
        clientsMutex.withLock {
            clientSockets.remove(clientId)?.let { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
            clientWriters.remove(clientId)
            clientLastHeartbeat.remove(clientId)
            peer = clientPeers.remove(clientId)
        }
        if (peer != null) {
            _connectedPeers.update { peers -> peers.filter { it != peer } }
        }
        logger.d(TAG, "Client $clientId disconnected and cleaned up")
    }

    /**
     * Send message to a specific client by ID.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun sendToClient(clientId: String, type: Int, message: ByteArray) = withContext(ioDispatcher) {
        val writer = clientsMutex.withLock { clientWriters[clientId] }

        if (writer == null) {
            logger.w(TAG, "Client channel not found for $clientId")
            return@withContext
        }

        try {
            val lanMessage = LanMessage(
                id = Uuid.random().toString(),
                timestamp = nowMillis(),
                payload = message,
                type = type
            )
            writer.writeDelimited(lanMessage.encode())
            // Update heartbeat on send
            clientsMutex.withLock {
                clientLastHeartbeat[clientId] = nowMillis()
            }
            logger.d(TAG, "Sent ${message.size} bytes to $clientId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Send error to $clientId", e)
            disconnectClient(clientId)
        }
    }

    /**
     * Broadcast message to all connected clients.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun broadcast(type: Int, message: ByteArray) = withContext(ioDispatcher) {
        val clientsToSend: Map<String, ByteWriteChannel> = clientsMutex.withLock { clientWriters.toMap() }

        if (clientsToSend.isEmpty()) {
            throw IllegalStateException("No clients connected")
        }

        val lanMessage = LanMessage(
            id = Uuid.random().toString(),
            timestamp = nowMillis(),
            payload = message,
            type = type
        )
        val frame = lanMessage.encode()

        val now = nowMillis()
        var sentCount = 0
        val failedClients = mutableListOf<String>()

        clientsToSend.forEach { (clientId, writer) ->
            try {
                writer.writeDelimited(frame)
                sentCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Broadcast send error to $clientId", e)
                failedClients.add(clientId)
            }
        }

        // Update heartbeats for all sent clients
        clientsMutex.withLock {
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

        logger.d(TAG, "Broadcast to $sentCount clients (${failedClients.size} failed)")
    }

    /**
     * Send message to all connected clients (legacy behavior for backward compatibility).
     * Prefer broadcast() for sending to all clients.
     */
    override suspend fun send(type: Int, data: ByteArray) {
        broadcast(type, data)
    }

    override fun stop() {
        heartbeatJob?.cancel()
        scope.cancel()

        // Close all client sockets. stop() is not suspend, so drain the maps via
        // runBlocking under the same mutex to keep access serialized.
        runBlocking {
            clientsMutex.withLock {
                clientSockets.values.forEach { socket ->
                    try { socket.close() } catch (_: Exception) {}
                }
                clientSockets.clear()
                clientWriters.clear()
                clientPeers.clear()
                clientLastHeartbeat.clear()
            }
        }

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        try {
            selector?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing selector", e)
        }
        selector = null
        _connectionState.value = ConnectionState.Idle
        _connectedPeers.value = emptyList()
        logger.d(TAG, "Server stopped")
    }
}
