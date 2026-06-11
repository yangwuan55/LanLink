package com.ymr.lanlink.core.data.discovery

data class DiscoveredPeer(
    val name: String,
    val host: String,
    val port: Int,
    // Stable server identity from the broadcast (empty when the advertiser is an
    // older build that does not yet ship it). Lets reconnect match a server by
    // identity rather than by its ephemeral TCP port.
    val serverDeviceId: String = ""
)

sealed class UdpDiscoveryState {
    object Idle : UdpDiscoveryState()
    object Broadcasting : UdpDiscoveryState()
    object Listening : UdpDiscoveryState()
    object Stopped : UdpDiscoveryState()
    data class Error(val message: String) : UdpDiscoveryState()
}