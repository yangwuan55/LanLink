package com.ymr.lanlink.core.data.socket

import com.ymr.lanlink.core.crypto.TokenProofs
import com.ymr.lanlink.core.crypto.constantTimeEquals
import com.ymr.lanlink.core.data.proto.AuthProtocol
import com.ymr.lanlink.core.data.proto.AuthRequest
import com.ymr.lanlink.core.data.proto.LanMessage
import com.ymr.lanlink.core.data.proto.TokenHello
import com.ymr.lanlink.core.data.proto.TokenProof
import com.ymr.lanlink.core.data.proto.decodeAuthResponse
import com.ymr.lanlink.core.data.proto.decodeLanMessage
import com.ymr.lanlink.core.data.proto.decodeTokenChallenge
import com.ymr.lanlink.core.data.proto.encode
import com.ymr.lanlink.core.data.proto.readDelimited
import com.ymr.lanlink.core.data.proto.writeDelimited
import com.ymr.lanlink.core.platform.secureRandomBytes
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.net.AuthHandshakeResult
import com.ymr.lanlink.core.net.LanClient
import com.ymr.lanlink.core.platform.ioDispatcher
import com.ymr.lanlink.core.platform.logger
import com.ymr.lanlink.core.platform.nowMillis
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * TCP Socket Client that connects to a TCP server.
 * Provides Flow-based APIs for connection state and messages.
 *
 * Ktor (commonMain) reimplementation of the former androidMain java.net client.
 * Framing is delegated to [writeDelimited]/[readDelimited] (varint length prefix).
 */
class TcpSocketClient(
    private val connectionTimeoutMs: Long = 10_000,
    private val readTimeoutMs: Long = 30_000,
    private val heartbeatIntervalMs: Long = 15_000,
    // Bounds each blocking read during the credential handshake so a stalled or
    // misbehaving server cannot hang authenticateWithCredential forever
    // (review MAJOR-2).
    private val handshakeTimeoutMs: Long = 5_000,
) : LanClient {
    private var selector: SelectorManager? = null
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null

    private var connected: Boolean = false
    /** True once the TCP connection and its read/write channels are established. */
    val isConnected: Boolean get() = connected

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var readLoopJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastHeartbeatSend: Long = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Each frame carries its wire type tag alongside the payload bytes.
    private val _messages = MutableSharedFlow<Pair<Int, ByteArray>>(replay = 0)
    override val messages: SharedFlow<Pair<Int, ByteArray>> = _messages.asSharedFlow()

    private var maxRetries = 3
    private var baseDelayMs = 1000L
    private var currentRetry = 0

    companion object {
        private const val TAG = "TcpSocketClient"
    }

    override suspend fun connect(peer: PeerInfo): Unit = withContext(ioDispatcher) {
        currentRetry = 0
        _connectionState.update { ConnectionState.Connecting }

        while (currentRetry < maxRetries && _connectionState.value is ConnectionState.Connecting) {
            try {
                logger.d(TAG, "Connecting to ${peer.host}:${peer.port} (attempt ${currentRetry + 1})")

                val newSelector = SelectorManager(ioDispatcher)
                val connectedSocket = withTimeout(connectionTimeoutMs) {
                    aSocket(newSelector).tcp().connect(InetSocketAddress(peer.host, peer.port))
                }
                selector = newSelector
                socket = connectedSocket
                readChannel = connectedSocket.openReadChannel()
                writeChannel = connectedSocket.openWriteChannel()
                connected = true

                _connectionState.update { ConnectionState.Connected(peer.name, false) }
                currentRetry = 0
                logger.d(TAG, "Connected to ${peer.host}:${peer.port}")

                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.e(TAG, "Socket error during connect", e)
                if (currentRetry < maxRetries - 1) {
                    currentRetry++
                    val delayMs = baseDelayMs * (1 shl currentRetry)  // Exponential backoff
                    logger.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                } else {
                    _connectionState.update { ConnectionState.Error("Connection failed: ${e.message}") }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Unexpected error during connect", e)
                _connectionState.update { ConnectionState.Error("Connection failed: ${e.message}") }
                break
            }
        }
    }

    override suspend fun authenticate(deviceName: String, credentials: ByteArray): AuthHandshakeResult =
        withContext(ioDispatcher) {
            val write = writeChannel ?: throw IllegalStateException("Not connected")
            val read = readChannel ?: throw IllegalStateException("Not connected")
            val request = AuthRequest(deviceName = deviceName, credentials = credentials)
            write.writeDelimited(request.encode())
            val response = decodeAuthResponse(read.readDelimited())
            logger.d(TAG, "Auth response: success=${response.success}")
            AuthHandshakeResult(
                response.success,
                response.message,
                customData = response.customData.takeIf { it.isNotEmpty() },
            )
        }

    /**
     * Credential direct-connect handshake (plan 3.4): sends TokenHello, verifies
     * the server proof in constant time (aborting WITHOUT sending a client proof
     * if it fails — defends against a spoofed server), then sends the client
     * proof. [pairingId] and [secret] come from the stored credential.
     */
    override suspend fun authenticateWithCredential(
        deviceName: String,
        pairingId: String,
        secret: ByteArray,
    ): AuthHandshakeResult = withContext(ioDispatcher) {
        val write = writeChannel ?: throw IllegalStateException("Not connected")
        val read = readChannel ?: throw IllegalStateException("Not connected")

        val clientNonce = secureRandomBytes(AuthProtocol.NONCE_BYTES)
        val hello = TokenHello(pairingId = pairingId, clientNonce = clientNonce)
        val request = AuthRequest(
            deviceName = deviceName,
            authScheme = AuthProtocol.AUTH_SCHEME_TOKEN,
            customData = hello.encode(),
        )
        write.writeDelimited(request.encode())

        // Each handshake read is bounded so a stalled server can't hang us.
        val challengeResponse = decodeAuthResponse(withTimeout(handshakeTimeoutMs) { read.readDelimited() })
        if (!challengeResponse.success) {
            // e.g. PAIRING_REVOKED — server rejected before any challenge.
            logger.w(TAG, "Token handshake rejected: ${challengeResponse.message}")
            return@withContext AuthHandshakeResult(false, challengeResponse.message)
        }
        // Explicitly require the intermediate-challenge marker AND a non-empty
        // customData before decoding, instead of inferring it from success alone
        // (review MINOR-4).
        if (challengeResponse.message != AuthProtocol.MESSAGE_CHALLENGE || challengeResponse.customData.isEmpty()) {
            logger.w(TAG, "Unexpected token handshake response: message=${challengeResponse.message}")
            return@withContext AuthHandshakeResult(false, AuthProtocol.REASON_SERVER_PROOF_MISMATCH)
        }

        val challenge = decodeTokenChallenge(challengeResponse.customData)
        val expectedServerProof = TokenProofs.serverProof(secret, clientNonce, challenge.serverNonce)
        if (!constantTimeEquals(challenge.serverProof, expectedServerProof)) {
            // Spoofed/incorrect server — abort WITHOUT revealing a client proof.
            logger.w(TAG, "Server proof mismatch; aborting without sending client proof")
            return@withContext AuthHandshakeResult(false, AuthProtocol.REASON_SERVER_PROOF_MISMATCH)
        }

        val clientProof = TokenProofs.clientProof(secret, challenge.serverNonce, clientNonce)
        write.writeDelimited(TokenProof(clientProof = clientProof).encode())

        val finalResponse = decodeAuthResponse(withTimeout(handshakeTimeoutMs) { read.readDelimited() })
        logger.d(TAG, "Token handshake final: success=${finalResponse.success}")
        AuthHandshakeResult(finalResponse.success, finalResponse.message)
    }

    override fun startReadLoop() {
        readLoopJob = scope.launch { readLoop() }
        startHeartbeat()
        logger.d(TAG, "Read loop and heartbeat started")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                delay(heartbeatIntervalMs)
                sendHeartbeatIfNeeded()
            }
        }
    }

    private suspend fun sendHeartbeatIfNeeded() {
        val now = nowMillis()
        if (now - lastHeartbeatSend >= heartbeatIntervalMs) {
            lastHeartbeatSend = now
            try {
                val pingMessage = LanMessage(
                    id = "heartbeat-$now",
                    timestamp = now,
                    payload = "__PING__".encodeToByteArray()
                )
                writeChannel?.let { channel ->
                    channel.writeDelimited(pingMessage.encode())
                    logger.d(TAG, "Heartbeat sent")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Heartbeat send failed, may be disconnected", e)
                // Don't disconnect here - let read loop detect the failure
            }
        }
    }

    private suspend fun readLoop() = withContext(ioDispatcher) {
        val read = readChannel ?: return@withContext
        try {
            while (_connectionState.value is ConnectionState.Connected) {
                try {
                    val frame = withTimeout(readTimeoutMs) { read.readDelimited() }
                    val lanMessage = decodeLanMessage(frame)
                    val bytes = lanMessage.payload

                    // Ignore heartbeat responses
                    if (lanMessage.id.startsWith("heartbeat-")) {
                        logger.d(TAG, "Heartbeat response received")
                        continue
                    }

                    logger.d(TAG, "Received ${bytes.size} bytes (type=${lanMessage.type})")
                    _messages.emit(lanMessage.type to bytes)
                } catch (e: TimeoutCancellationException) {
                    logger.w(TAG, "Read timeout, connection may be dead")
                    break
                } catch (e: IOException) {
                    // Covers EOF / peer-closed reads (kotlinx.io.EOFException) and other I/O errors.
                    logger.w(TAG, "Read error, connection may be lost", e)
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Read loop error", e)
        } finally {
            if (_connectionState.value is ConnectionState.Connected) {
                disconnect()
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun send(type: Int, data: ByteArray): Unit = withContext(ioDispatcher) {
        val write = writeChannel ?: throw IllegalStateException("Not connected")
        try {
            val lanMessage = LanMessage(
                id = Uuid.random().toString(),
                timestamp = nowMillis(),
                payload = data,
                type = type
            )
            write.writeDelimited(lanMessage.encode())
            logger.d(TAG, "Sent ${data.size} bytes (type=$type)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Send error", e)
            throw e
        }
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        readLoopJob?.cancel()
        connected = false
        readChannel = null
        writeChannel = null
        try {
            socket?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing socket", e)
        }
        socket = null
        try {
            selector?.close()
        } catch (e: Exception) {
            logger.e(TAG, "Error closing selector", e)
        }
        selector = null
        _connectionState.update { ConnectionState.Idle }
        logger.d(TAG, "Disconnected")
    }
}
