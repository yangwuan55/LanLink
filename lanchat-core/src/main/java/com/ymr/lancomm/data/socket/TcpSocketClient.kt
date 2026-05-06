package com.ymr.lancomm.data.socket

import android.util.Log
import com.google.protobuf.ByteString
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import com.ymr.lancomm.proto.LanMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException

/**
 * TCP Socket Client that connects to a TCP server.
 * Provides Flow-based APIs for connection state and messages.
 */
class TcpSocketClient(
    private val connectionTimeoutMs: Long = 10_000,
    private val readTimeoutMs: Long = 30_000,
    private val heartbeatIntervalMs: Long = 15_000
) {
    private var socket: Socket? = null
    private var _protobufChannel: ProtobufChannel? = null
    val protobufChannel: ProtobufChannel?
        get() = _protobufChannel
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readLoopJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastHeartbeatSend: Long = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<ByteArray>(replay = 0)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private var maxRetries = 3
    private var baseDelayMs = 1000L
    private var currentRetry = 0

    companion object {
        private const val TAG = "TcpSocketClient"
    }

    suspend fun connect(peer: PeerInfo) = withContext(Dispatchers.IO) {
        currentRetry = 0
        _connectionState.update { ConnectionState.Connecting }

        while (currentRetry < maxRetries && _connectionState.value is ConnectionState.Connecting) {
            try {
                Log.d(TAG, "Connecting to ${peer.host}:${peer.port} (attempt ${currentRetry + 1})")

                socket = Socket().apply {
                    connect(java.net.InetSocketAddress(peer.host.hostAddress, peer.port), connectionTimeoutMs.toInt())
                    soTimeout = readTimeoutMs.toInt()
                    keepAlive = true
                }

                // Create ProtobufChannel for protobuf-based communication
                _protobufChannel = ProtobufChannel(socket!!.getInputStream(), socket!!.getOutputStream())

                _connectionState.update { ConnectionState.Connected(peer.name, false) }
                currentRetry = 0
                Log.d(TAG, "Connected to ${peer.host}:${peer.port}")

                // Start read loop and heartbeat
                readLoopJob = scope.launch { readLoop() }
                startHeartbeat()

                return@withContext

            } catch (e: UnknownHostException) {
                Log.e(TAG, "Unknown host: ${peer.host}", e)
                _connectionState.update { ConnectionState.Error("Unknown host: ${e.message}") }
                break
            } catch (e: SocketException) {
                Log.e(TAG, "Socket error during connect", e)
                if (currentRetry < maxRetries - 1) {
                    currentRetry++
                    val delayMs = baseDelayMs * (1 shl currentRetry)  // Exponential backoff
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                } else {
                    _connectionState.update { ConnectionState.Error("Connection failed: ${e.message}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during connect", e)
                _connectionState.update { ConnectionState.Error("Connection failed: ${e.message}") }
                break
            }
        }
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
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatSend >= heartbeatIntervalMs) {
            lastHeartbeatSend = now
            try {
                // Send a ping message as heartbeat
                val pingMessage = LanMessage.newBuilder()
                    .setId("heartbeat-$now")
                    .setTimestamp(now)
                    .setPayload(ByteString.copyFromUtf8("__PING__"))
                    .build()
                _protobufChannel?.let { channel ->
                    channel.send(pingMessage)
                    Log.d(TAG, "Heartbeat sent")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat send failed, may be disconnected", e)
                // Don't disconnect here - let read loop detect the failure
            }
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        socket?.let { sock ->
            val channel = _protobufChannel ?: return@withContext
            try {
                while (_connectionState.value is ConnectionState.Connected && !sock.isClosed) {
                    try {
                        val lanMessage = channel.receiveLanMessage()
                        val bytes = lanMessage.payload.toByteArray()

                        // Ignore heartbeat responses
                        if (lanMessage.id.startsWith("heartbeat-")) {
                            Log.d(TAG, "Heartbeat response received")
                            continue
                        }

                        Log.d(TAG, "Received ${bytes.size} bytes")
                        _messages.emit(bytes)
                    } catch (e: SocketException) {
                        Log.d(TAG, "Read error, connection may be lost", e)
                        break
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Read timeout, connection may be dead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read loop error", e)
            } finally {
                if (_connectionState.value is ConnectionState.Connected) {
                    disconnect()
                }
            }
        }
    }

    suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        val channel = _protobufChannel ?: throw IllegalStateException("Not connected")
        try {
            val lanMessage = LanMessage.newBuilder()
                .setId(java.util.UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .setPayload(ByteString.copyFrom(message))
                .build()
            channel.send(lanMessage)
            Log.d(TAG, "Sent ${message.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            throw e
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        readLoopJob?.cancel()
        _protobufChannel = null
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
        _connectionState.update { ConnectionState.Idle }
        Log.d(TAG, "Disconnected")
    }
}
