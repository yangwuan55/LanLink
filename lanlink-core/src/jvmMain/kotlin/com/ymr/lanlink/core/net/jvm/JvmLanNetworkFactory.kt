package com.ymr.lanlink.core.net.jvm

import com.ymr.lanlink.core.data.discovery.UdpDiscoveryClient
import com.ymr.lanlink.core.data.discovery.UdpDiscoveryServer
import com.ymr.lanlink.core.data.socket.TcpSocketClient
import com.ymr.lanlink.core.data.socket.TcpSocketServer
import com.ymr.lanlink.core.net.DiscoveryAdvertiser
import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.net.LanClient
import com.ymr.lanlink.core.net.LanNetworkFactory
import com.ymr.lanlink.core.net.LanServer
import com.ymr.lanlink.core.platform.deviceName

/**
 * JVM implementation of [LanNetworkFactory]: wires the TCP socket transport and
 * UDP discovery components behind the common interfaces.
 *
 * Unlike the Android factory it does not use NSD; discovery is the pure-Kotlin
 * UDP broadcast pair ([UdpDiscoveryServer] / [UdpDiscoveryClient]) from
 * commonMain, so it has no platform dependency (no Android Context needed).
 */
class JvmLanNetworkFactory : LanNetworkFactory {

    override fun createServer(
        pairingRegistry: com.ymr.lanlink.core.domain.pairing.PairingRegistry,
        serverDeviceId: String,
        serverName: String,
    ): LanServer =
        TcpSocketServer(
            port = 0,
            // Pairing window starts closed; opened later via setPairingPin().
            authProvider = null,
            pairingRegistry = pairingRegistry,
            serverDeviceId = serverDeviceId,
            serverName = serverName,
        )

    override fun createClient(): LanClient =
        TcpSocketClient()

    override fun createAdvertiser(servicePort: Int): DiscoveryAdvertiser =
        UdpDiscoveryServer(servicePort, deviceName())

    override fun createScanner(): DiscoveryScanner =
        UdpDiscoveryClient()
}
