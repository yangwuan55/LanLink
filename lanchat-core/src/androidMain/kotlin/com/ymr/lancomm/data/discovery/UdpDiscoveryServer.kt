package com.ymr.lancomm.data.discovery

import android.util.Log
import com.ymr.lancomm.net.DiscoveryAdvertiser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpDiscoveryServer(
    private val servicePort: Int,
    private val deviceName: String = android.os.Build.MODEL
) : DiscoveryAdvertiser {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

        Log.d(TAG, "Starting UDP Discovery Server with broadcast interval: ${BROADCAST_INTERVAL_MS}ms")

        scope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    setBroadcast(true)
                }
                _state.value = UdpDiscoveryState.Broadcasting
                Log.d(TAG, "UDP Discovery Server started on port $DISCOVERY_PORT")

                while (isRunning) {
                    broadcastPresence()
                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP Discovery Server error", e)
                _state.value = UdpDiscoveryState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun broadcastPresence() {
        socket?.let { sock ->
            try {
                val localIp = getLocalIpAddress()
                Log.d(TAG, "Detected local IP: $localIp")
                val message = "$MESSAGE|$deviceName|$localIp|$servicePort"
                val data = message.toByteArray()
                val address = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, address, DISCOVERY_PORT)
                sock.send(packet)
                Log.d(TAG, "Broadcast sent: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast error", e)
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return "127.0.0.1"
    }

    override fun stop() {
        isRunning = false
        scope.cancel()
        socket?.close()
        socket = null
        _state.value = UdpDiscoveryState.Idle
        Log.d(TAG, "UDP Discovery Server stopped")
    }
}
