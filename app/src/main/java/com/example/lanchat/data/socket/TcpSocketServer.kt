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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class TcpSocketServer(
    private val port: Int = 0,  // 0 = dynamic port allocation
    private val callback: SocketCallback
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var actualPort: Int = 0

    companion object {
        private const val TAG = "TcpSocketServer"
    }

    suspend fun start(): Int = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(port).apply {
                soTimeout = 0  // No timeout
            }
            actualPort = serverSocket!!.localPort
            _isRunning.value = true
            Log.d(TAG, "Server started on port $actualPort")

            // Start accept loop in SEPARATE coroutine so this function can return
            scope.launch {
                acceptLoop()
            }
            
            actualPort
        } catch (e: Exception) {
            Log.e(TAG, "Server start error", e)
            callback.onError(e)
            throw e
        }
    }
    
    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
            try {
                clientSocket = serverSocket!!.accept()
                Log.d(TAG, "Client connected from ${clientSocket!!.remoteSocketAddress}")

                // Create protobuf channel with the client socket's streams
                val inputStream = clientSocket!!.getInputStream()
                val outputStream = clientSocket!!.getOutputStream()
                callback.onConnected(inputStream, outputStream)

                // Handle client in separate coroutine
                scope.launch {
                    handleClient(clientSocket!!)
                }
            } catch (e: SocketException) {
                if (e.message != "Socket closed") {
                    Log.e(TAG, "Accept error", e)
                    callback.onError(e)
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            while (isActive && !socket.isClosed && socket.isConnected) {
                kotlinx.coroutines.delay(100)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client handler error", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client socket", e)
            }
            callback.onDisconnected()
        }
    }

    suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        clientSocket?.let { socket ->
            try {
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                writer.write(String(message, Charsets.UTF_8))
                writer.newLine()
                writer.flush()
                Log.d(TAG, "Sent ${message.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                callback.onError(e)
            }
        } ?: run {
            Log.w(TAG, "No client connected, cannot send")
        }
    }

    fun stop() {
        scope.cancel()
        try {
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        clientSocket = null
        _isRunning.value = false
        Log.d(TAG, "Server stopped")
    }
}