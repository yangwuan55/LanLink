package com.ymr.lanlink.core.data.discovery

import com.ymr.lanlink.core.net.DiscoveryAdvertiser
import com.ymr.lanlink.core.platform.deviceName
import com.ymr.lanlink.core.platform.ioDispatcher
import com.ymr.lanlink.core.platform.localIpv4Address
import com.ymr.lanlink.core.platform.logger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.Buffer

class UdpDiscoveryServer(
    private val servicePort: Int,
    private val deviceName: String = deviceName()
) : DiscoveryAdvertiser {

    private var selector: SelectorManager? = null
    private var socket: io.ktor.network.sockets.BoundDatagramSocket? = null
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var isRunning = false

    private val _state = MutableStateFlow<UdpDiscoveryState>(UdpDiscoveryState.Idle)
    val state: StateFlow<UdpDiscoveryState> = _state.asStateFlow()

    companion object {
        private const val TAG = "UdpDiscoveryServer"
        private const val DISCOVERY_PORT = 45678
        private const val BROADCAST_INTERVAL_MS = 2000L
        private const val MESSAGE = "LANCHAT_DISCOVER"
    }

    override fun start() {
        if (isRunning) return
        isRunning = true

        logger.d(TAG, "Starting UDP Discovery Server with broadcast interval: ${BROADCAST_INTERVAL_MS}ms")

        scope.launch {
            try {
                val newSelector = SelectorManager(ioDispatcher)
                selector = newSelector
                val boundSocket = aSocket(newSelector).udp()
                    .bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT)) {
                        broadcast = true
                        reuseAddress = true
                    }
                socket = boundSocket
                _state.value = UdpDiscoveryState.Broadcasting
                logger.d(TAG, "UDP Discovery Server started on port $DISCOVERY_PORT")

                while (isRunning) {
                    broadcastPresence(boundSocket)
                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "UDP Discovery Server error", e)
                _state.value = UdpDiscoveryState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun broadcastPresence(boundSocket: io.ktor.network.sockets.BoundDatagramSocket) {
        try {
            val localIp = localIpv4Address()
            logger.d(TAG, "Detected local IP: $localIp")
            val message = "$MESSAGE|$deviceName|$localIp|$servicePort"
            val messageBytes = message.encodeToByteArray()
            val buffer = Buffer().apply { write(messageBytes) }
            val address = InetSocketAddress("255.255.255.255", DISCOVERY_PORT)
            val datagram = Datagram(buffer, address)
            boundSocket.send(datagram)
            logger.d(TAG, "Broadcast sent: $message")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Broadcast error", e)
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
        _state.value = UdpDiscoveryState.Idle
        logger.d(TAG, "UDP Discovery Server stopped")
    }
}
