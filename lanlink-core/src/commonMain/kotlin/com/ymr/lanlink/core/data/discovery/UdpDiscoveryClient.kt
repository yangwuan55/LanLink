package com.ymr.lanlink.core.data.discovery

import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.platform.ioDispatcher
import com.ymr.lanlink.core.platform.logger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UdpDiscoveryClient(
    private val ignoredServicePorts: Set<Int> = emptySet()
) : DiscoveryScanner {

    private var selector: SelectorManager? = null
    private var socket: io.ktor.network.sockets.BoundDatagramSocket? = null
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var isRunning = false

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _state = MutableStateFlow<UdpDiscoveryState>(UdpDiscoveryState.Idle)
    val state: StateFlow<UdpDiscoveryState> = _state.asStateFlow()

    companion object {
        private const val TAG = "UdpDiscoveryClient"
        private const val LISTEN_PORT = 45678
        private const val MESSAGE_PREFIX = "LANCHAT_DISCOVER"
    }

    override fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                val newSelector = SelectorManager(ioDispatcher)
                selector = newSelector
                val boundSocket = aSocket(newSelector).udp()
                    .bind(InetSocketAddress("0.0.0.0", LISTEN_PORT)) {
                        broadcast = true
                        reuseAddress = true
                    }
                socket = boundSocket
                _state.value = UdpDiscoveryState.Listening
                logger.d(TAG, "UDP Discovery Client started on port $LISTEN_PORT")

                while (isRunning) {
                    val datagram = boundSocket.receive()
                    val message = datagram.packet.readByteArray().decodeToString()
                    val fromHost = (datagram.address as? InetSocketAddress)?.hostname ?: ""
                    handleMessage(message, fromHost)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRunning) {
                    logger.e(TAG, "UDP Discovery Client error", e)
                    _state.value = UdpDiscoveryState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun handleMessage(message: String, fromHost: String) {
        logger.d(TAG, "Received message: $message from $fromHost")
        if (message.startsWith(MESSAGE_PREFIX)) {
            val parts = message.split("|")
            if (parts.size >= 4) {
                val name = parts[1]
                val host = parts[2]
                val port = parts[3].toIntOrNull() ?: return
                if (port in ignoredServicePorts) {
                    logger.d(TAG, "Ignoring local peer announcement on port $port")
                    return
                }

                logger.d(TAG, "Discovered peer: $name at $host:$port")

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
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        try {
            selector?.close()
        } catch (_: Exception) {}
        selector = null
        _state.value = UdpDiscoveryState.Stopped
        _discoveredPeers.value = emptyList()
        logger.d(TAG, "UDP Discovery Client stopped")
    }
}
