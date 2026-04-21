package com.ymr.lancomm.data.socket

import android.util.Log
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException

/**
 * TCP Socket Client that connects to a TCP server.
 * Provides Flow-based APIs for connection state and messages.
 */
class TcpSocketClient {
    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readLoopJob: Job? = null

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
        _connectionState.value = ConnectionState.Connecting
        
        while (currentRetry < maxRetries && _connectionState.value is ConnectionState.Connecting) {
            try {
                Log.d(TAG, "Connecting to ${peer.host}:${peer.port} (attempt ${currentRetry + 1})")

                socket = Socket(peer.host.hostAddress, peer.port).apply {
                    soTimeout = 0  // No timeout
                }
                
                _connectionState.value = ConnectionState.Connected(peer.name, false)
                currentRetry = 0
                Log.d(TAG, "Connected to ${peer.host}:${peer.port}")

                // Start read loop
                readLoopJob = scope.launch {
                    readLoop()
                }

                return@withContext

            } catch (e: UnknownHostException) {
                Log.e(TAG, "Unknown host: ${peer.host}", e)
                _connectionState.value = ConnectionState.Error("Unknown host: ${e.message}")
                break
            } catch (e: SocketException) {
                Log.e(TAG, "Socket error during connect", e)
                if (currentRetry < maxRetries - 1) {
                    currentRetry++
                    val delayMs = baseDelayMs * (1 shl currentRetry)  // Exponential backoff
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                } else {
                    _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during connect", e)
                _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
                break
            }
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        socket?.let { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))

                while (_connectionState.value is ConnectionState.Connected && !sock.isClosed) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            val bytes = line.toByteArray()
                            Log.d(TAG, "Received ${bytes.size} bytes")
                            _messages.emit(bytes)
                        } else {
                            // Server disconnected
                            Log.d(TAG, "Server disconnected")
                            _connectionState.value = ConnectionState.Idle
                            break
                        }
                    } catch (e: SocketException) {
                        Log.d(TAG, "Read error", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read loop error", e)
            } finally {
                disconnect()
            }
        }
    }

    suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        socket?.let { sock ->
            try {
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                writer.write(String(message, Charsets.UTF_8))
                writer.newLine()
                writer.flush()
                Log.d(TAG, "Sent ${message.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                throw e
            }
        } ?: run {
            Log.w(TAG, "Not connected, cannot send")
            throw IllegalStateException("Not connected")
        }
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.Idle
        readLoopJob?.cancel()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
        Log.d(TAG, "Disconnected")
    }
}
