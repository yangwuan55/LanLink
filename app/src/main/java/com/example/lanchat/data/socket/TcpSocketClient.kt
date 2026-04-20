package com.example.lanchat.data.socket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException

class TcpSocketClient(private val callback: SocketCallback) {
    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var maxRetries = 3
    private var baseDelayMs = 1000L
    private var currentRetry = 0

    companion object {
        private const val TAG = "TcpSocketClient"
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        currentRetry = 0
        while (currentRetry < maxRetries && !_isConnected.value) {
            try {
                Log.d(TAG, "Connecting to $host:$port (attempt ${currentRetry + 1})")

                val newSocket = Socket(host, port).apply {
                    soTimeout = 0  // No timeout
                }
                socket = newSocket
                _isConnected.value = true
                currentRetry = 0  // Reset on success
                Log.d(TAG, "Connected to $host:$port")
                callback.onConnected(newSocket.getInputStream(), newSocket.getOutputStream())

                // Start reading in loop
                readLoop()

            } catch (e: UnknownHostException) {
                Log.e(TAG, "Unknown host: $host", e)
                callback.onError(e)
                break
            } catch (e: SocketException) {
                Log.e(TAG, "Socket error during connect", e)
                callback.onError(e)
                if (currentRetry < maxRetries - 1) {
                    currentRetry++
                    val delayMs = baseDelayMs * (1 shl currentRetry)  // Exponential backoff
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during connect", e)
                callback.onError(e)
                break
            }
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        socket?.let { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))

                while (_isConnected.value && !sock.isClosed) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            val bytes = line.toByteArray()
                            callback.onMessageReceived(bytes)
                        } else {
                            // Server disconnected
                            Log.d(TAG, "Server disconnected")
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
                callback.onError(e)
            }
        } ?: run {
            Log.w(TAG, "Not connected, cannot send")
        }
    }

    fun disconnect() {
        _isConnected.value = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
        callback.onDisconnected()
        Log.d(TAG, "Disconnected")
    }
}