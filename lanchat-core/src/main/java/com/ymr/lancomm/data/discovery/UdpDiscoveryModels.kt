package com.ymr.lancomm.data.discovery

import java.net.InetAddress

data class DiscoveredPeer(
    val name: String,
    val host: InetAddress,
    val port: Int
)

sealed class UdpDiscoveryState {
    object Idle : UdpDiscoveryState()
    object Broadcasting : UdpDiscoveryState()
    object Listening : UdpDiscoveryState()
    object Stopped : UdpDiscoveryState()
    data class Error(val message: String) : UdpDiscoveryState()
}