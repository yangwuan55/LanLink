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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP Socket Server that accepts multiple client connections.
 * Provides Flow-based APIs for connection state, peer list, and messages.
 */
class TcpSocketServer(
    private val port: Int = 0
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Track connected clients by their socket
    private val clientSockets = mutableMapOf<String, Socket>()
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            while (isActive && !socket.isClosed && socket.isConnected) {
                try {
                    val line = reader.readLine()
                    if (line != null) {
                        val bytes = line.toByteArray()
                        Log.d(TAG, "Received ${bytes.size} bytes from $clientId")
                        _messages.emit(bytes)
                    } else {
                        // Client disconnected
                        Log.d(TAG, "Client $clientId disconnected")
                        break
                    }
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
            // Remove socket and peer
            synchronized(clientSocketsLock) {
                clientSockets.remove(clientId)
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

        // Send to first connected peer (for simplicity)
        val peer = currentPeers.first()
        val clientId = peer.name
        
        val socket = synchronized(clientSocketsLock) {
            clientSockets[clientId]
        }
        
        socket?.let { sock ->
            try {
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                writer.write(String(message, Charsets.UTF_8))
                writer.newLine()
                writer.flush()
                Log.d(TAG, "Sent ${message.size} bytes to ${peer.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                throw e
            }
        } ?: run {
            Log.w(TAG, "Client socket not found for ${peer.name}")
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
