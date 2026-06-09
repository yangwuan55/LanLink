package com.ymr.lancomm.data.discovery

import android.util.Log
import com.ymr.lancomm.net.DiscoveryScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpDiscoveryClient(
    private val ignoredServicePorts: Set<Int> = emptySet()
) : DiscoveryScanner {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _state = MutableStateFlow<UdpDiscoveryState>(UdpDiscoveryState.Idle)
    val state: StateFlow<UdpDiscoveryState> = _state.asStateFlow()

    companion object {
        private const val TAG = "UdpDiscoveryClient"
        private const val LISTEN_PORT = 45678  // Must match server's broadcast port
        private const val MESSAGE_PREFIX = "LANCHAT_DISCOVER"
    }

    override fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LISTEN_PORT))
                    setBroadcast(true)
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
                if (port in ignoredServicePorts) {
                    Log.d(TAG, "Ignoring local peer announcement on port $port")
                    return
                }

                Log.d(TAG, "Discovered peer: $name at $host:$port")

                val peer = DiscoveredPeer(name, host, port)
                val current = _discoveredPeers.value.toMutableList()
                current.removeAll { it.host == host && it.port == port }
                current.add(peer)
                _discoveredPeers.value = current
            }
        }
    }

    override fun stop() {
        isRunning = false
        scope.cancel()
        socket?.close()
        socket = null
        _state.value = UdpDiscoveryState.Stopped
        _discoveredPeers.value = emptyList()
        Log.d(TAG, "UDP Discovery Client stopped")
    }
}
