package com.example.lanchat.data.discovery

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpDiscoveryClient {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _state = MutableStateFlow<UdpDiscoveryState>(UdpDiscoveryState.Idle)
    val state: StateFlow<UdpDiscoveryState> = _state.asStateFlow()

    companion object {
        private const val TAG = "UdpDiscoveryClient"
        private const val LISTEN_PORT = 45678  // Must match server's broadcast port
        private const val MESSAGE_PREFIX = "LANCHAT_DISCOVER"
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                socket = DatagramSocket(LISTEN_PORT).apply {
                    setBroadcast(true)
                    reuseAddress = true
                }
                _state.value = UdpDiscoveryState.Listening
                Log.d(TAG, "UDP Discovery Client started on port $LISTEN_PORT")

                val buffer = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    handleMessage(message, packet.address.hostAddress ?: "", packet.port)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "UDP Discovery Client error", e)
                    _state.value = UdpDiscoveryState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun handleMessage(message: String, fromHost: String, fromPort: Int) {
        Log.d(TAG, "Received message: $message from $fromHost:$fromPort")
        if (message.startsWith(MESSAGE_PREFIX)) {
            val parts = message.split("|")
            if (parts.size >= 4) {
                val name = parts[1]
                val host = parts[2]
                val port = parts[3].toIntOrNull() ?: return

                Log.d(TAG, "Discovered peer: $name at $host:$port")

                val peer = DiscoveredPeer(name, host, port)
                val current = _discoveredPeers.value.toMutableList()
                current.removeAll { it.host == host && it.port == port }
                current.add(peer)
                _discoveredPeers.value = current
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        socket?.close()
        socket = null
        _state.value = UdpDiscoveryState.Stopped
        _discoveredPeers.value = emptyList()
        Log.d(TAG, "UDP Discovery Client stopped")
    }
}