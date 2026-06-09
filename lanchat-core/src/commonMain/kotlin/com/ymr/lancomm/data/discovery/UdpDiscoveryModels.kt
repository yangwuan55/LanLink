package com.ymr.lancomm.data.discovery

data class DiscoveredPeer(
    val name: String,
    val host: String,
    val port: Int
)

sealed class UdpDiscoveryState {
    object Idle : UdpDiscoveryState()
    object Broadcasting : UdpDiscoveryState()
    object Listening : UdpDiscoveryState()
    object Stopped : UdpDiscoveryState()
    data class Error(val message: String) : UdpDiscoveryState()
}