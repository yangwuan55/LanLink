package com.ymr.lanlink.core.data.socket

import com.ymr.lanlink.core.crypto.TokenProofs
import com.ymr.lanlink.core.crypto.constantTimeEquals
import com.ymr.lanlink.core.data.auth.InMemoryAuthProvider
import com.ymr.lanlink.core.data.proto.AuthProtocol
import com.ymr.lanlink.core.data.proto.AuthResponse
import com.ymr.lanlink.core.data.proto.IssuedPairing
import com.ymr.lanlink.core.data.proto.LanMessage
import com.ymr.lanlink.core.data.proto.TokenChallenge
import com.ymr.lanlink.core.data.proto.decodeAuthRequest
import com.ymr.lanlink.core.data.proto.decodeLanMessage
import com.ymr.lanlink.core.data.proto.decodeTokenHello
import com.ymr.lanlink.core.data.proto.decodeTokenProof
import com.ymr.lanlink.core.data.proto.encode
import com.ymr.lanlink.core.data.proto.readDelimited
import com.ymr.lanlink.core.data.proto.writeDelimited
import com.ymr.lanlink.core.domain.auth.AuthProvider
import com.ymr.lanlink.core.domain.auth.AuthResult
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.domain.pairing.PairingRecord
import com.ymr.lanlink.core.domain.pairing.PairingRegistry
import com.ymr.lanlink.core.net.LanServer
import com.ymr.lanlink.core.platform.ioDispatcher
import com.ymr.lanlink.core.platform.logger
import com.ymr.lanlink.core.platform.nowMillis
import com.ymr.lanlink.core.platform.secureRandomBytes
import io.ktor.utils.io.ByteReadChannel
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
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
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
    // Initial PIN pairing-window provider. null = window closed (authScheme=0 is
    // rejected); non-null = window open and PIN handshakes are validated against
    // it. Swap at runtime via [setPairingPin]. ALL reads/writes go through the
    // @Volatile [authProvider] field below so a closed/opened window is observed
    // by in-flight handler coroutines.
    authProvider: AuthProvider? = null,
    private val connectionTimeoutMs: Long = 30_000,
    private val heartbeatIntervalMs: Long = 15_000,
    // Credential direct-connect support. When null the server only speaks the
    // legacy PIN path (authScheme=0) and never mints credentials, preserving the
    // original behavior. When supplied, a successful PIN handshake mints a
    // PairingRecord and token (authScheme=1) reconnects are accepted.
    private val pairingRegistry: PairingRegistry? = null,
    private val serverDeviceId: String = "",
    private val serverName: String = "",
    // Bounds each blocking read during the token handshake so a client that
    // sends a valid TokenHello then goes silent cannot pin a handler coroutine +
    // socket open indefinitely (review MAJOR-2). PIN path does not read a second
    // frame so it is unaffected.
    private val handshakeTimeoutMs: Long = 5_000,
) : LanServer {
    private var selector: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    // The live PIN pairing-window provider. null = window closed. Volatile so a
    // setPairingPin() on one coroutine is seen by handler coroutines reading it
    // during a handshake.
    @Volatile
    private var authProvider: AuthProvider? = authProvider

    override fun setPairingPin(pin: String?) {
        // Each open rebuilds the provider so brute-force lockout state from a prior
        // window does not carry over (plan §3). Deliberate trade-off: lockout
        // guards online brute-force of a FIXED pin, but every window carries a
        // fresh pin, so resetting the counter does not help an attacker — closing
        // and reopening the window rotates the secret they would be guessing.
        authProvider = pin?.let { InMemoryAuthProvider(it) }
        logger.d(TAG, "Pairing window ${if (pin != null) "opened" else "closed"}")
    }

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
            logger.d(TAG, "Received AuthRequest from $clientId: deviceName=${authRequest.deviceName}, scheme=${authRequest.authScheme}")

            val authOk = if (authRequest.authScheme == AuthProtocol.AUTH_SCHEME_TOKEN) {
                handleTokenHandshake(authRequest, read, write, clientId, socket)
            } else {
                handlePinHandshake(authRequest, write, clientId)
            }

            // If authentication failed, the branch already sent its AuthResponse
            // and we close immediately.
            if (!authOk) {
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

    /**
     * Legacy PIN path (authScheme=0). On success, mints + persists a
     * [PairingRecord] when a [pairingRegistry] is configured and ships it back in
     * [AuthResponse.customData] as an [IssuedPairing]. Returns true if authenticated.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun handlePinHandshake(
        authRequest: com.ymr.lanlink.core.data.proto.AuthRequest,
        write: ByteWriteChannel,
        clientId: String,
    ): Boolean {
        // Pairing window closed: reject every PIN handshake. Token reconnects
        // (authScheme=1) reach handleTokenHandshake instead and are unaffected.
        val provider = authProvider
        if (provider == null) {
            write.writeDelimited(AuthResponse(success = false, message = AuthProtocol.REASON_PAIRING_CLOSED).encode())
            logger.w(TAG, "PIN handshake rejected for $clientId: pairing window closed")
            return false
        }

        val authResult = provider.authenticate(authRequest.deviceName, authRequest.credentials)

        if (authResult is AuthResult.Failure) {
            write.writeDelimited(AuthResponse(success = false, message = authResult.message).encode())
            logger.w(TAG, "Authentication failed for $clientId: ${authResult.message}")
            return false
        }

        // Mint a pairing credential when issuance is enabled.
        var customData = ByteArray(0)
        val registry = pairingRegistry
        if (registry != null) {
            val pairingId = Uuid.random().toString()
            val secret = secureRandomBytes(AuthProtocol.SECRET_BYTES)
            val now = nowMillis()
            registry.save(
                PairingRecord(
                    pairingId = pairingId,
                    secret = secret,
                    clientDeviceName = authRequest.deviceName,
                    pairedAt = now,
                    lastSeenAt = now,
                )
            )
            customData = IssuedPairing(
                pairingId = pairingId,
                secret = secret,
                serverDeviceId = serverDeviceId,
                serverName = serverName,
            ).encode()
            logger.d(TAG, "Issued pairing $pairingId to $clientId")
        }

        write.writeDelimited(AuthResponse(success = true, message = AuthProtocol.MESSAGE_OK, customData = customData).encode())
        return true
    }

    /**
     * Credential direct-connect path (authScheme=1): the HMAC-SHA256
     * challenge-response state machine (plan 3.4). Returns true once the client
     * proof verifies. All proof comparisons are constant-time.
     */
    private suspend fun handleTokenHandshake(
        authRequest: com.ymr.lanlink.core.data.proto.AuthRequest,
        read: ByteReadChannel,
        write: ByteWriteChannel,
        clientId: String,
        socket: Socket,
    ): Boolean {
        val registry = pairingRegistry
        if (registry == null) {
            write.writeDelimited(AuthResponse(success = false, message = AuthProtocol.REASON_PAIRING_REVOKED).encode())
            return false
        }

        val hello = decodeTokenHello(authRequest.customData)
        val record = registry.find(hello.pairingId)
        if (record == null) {
            logger.w(TAG, "Unknown pairingId ${hello.pairingId} from $clientId")
            write.writeDelimited(AuthResponse(success = false, message = AuthProtocol.REASON_PAIRING_REVOKED).encode())
            return false
        }

        val serverNonce = secureRandomBytes(AuthProtocol.NONCE_BYTES)
        val serverProof = TokenProofs.serverProof(record.secret, hello.clientNonce, serverNonce)
        val challenge = TokenChallenge(serverNonce = serverNonce, serverProof = serverProof)
        write.writeDelimited(
            AuthResponse(success = true, message = AuthProtocol.MESSAGE_CHALLENGE, customData = challenge.encode()).encode()
        )

        // Await the client proof under a bounded timeout so a half-open client
        // that goes silent after the challenge cannot pin this coroutine + socket
        // indefinitely (review MAJOR-2).
        val proofFrame = try {
            withTimeout(handshakeTimeoutMs) { decodeTokenProof(read.readDelimited()) }
        } catch (e: TimeoutCancellationException) {
            logger.w(TAG, "Token handshake timeout awaiting client proof for ${hello.pairingId} ($clientId)")
            try { socket.close() } catch (_: Exception) {}
            return false
        }
        val expected = TokenProofs.clientProof(record.secret, serverNonce, hello.clientNonce)
        if (!constantTimeEquals(proofFrame.clientProof, expected)) {
            logger.w(TAG, "Invalid client proof for ${hello.pairingId} from $clientId")
            write.writeDelimited(AuthResponse(success = false, message = AuthProtocol.REASON_INVALID_PROOF).encode())
            return false
        }

        registry.save(record.copy(lastSeenAt = nowMillis()))
        write.writeDelimited(AuthResponse(success = true, message = AuthProtocol.MESSAGE_OK).encode())
        logger.d(TAG, "Token handshake OK for ${hello.pairingId} ($clientId)")
        return true
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
