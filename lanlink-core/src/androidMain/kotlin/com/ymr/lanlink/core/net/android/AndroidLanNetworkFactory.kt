package com.ymr.lanlink.core.net.android

import com.ymr.lanlink.core.data.discovery.UdpDiscoveryClient
import com.ymr.lanlink.core.data.discovery.UdpDiscoveryServer
import com.ymr.lanlink.core.data.socket.TcpSocketClient
import com.ymr.lanlink.core.data.socket.TcpSocketServer
import com.ymr.lanlink.core.net.DiscoveryAdvertiser
import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.net.LanClient
import com.ymr.lanlink.core.net.LanNetworkFactory
import com.ymr.lanlink.core.net.LanServer
import com.ymr.lanlink.core.platform.AndroidLoggerInstaller
import com.ymr.lanlink.core.platform.deviceName

/**
 * Android implementation of [LanNetworkFactory]: wires the TCP socket transport
 * and UDP discovery components behind the common interfaces.
 */
class AndroidLanNetworkFactory : LanNetworkFactory {

    init {
        // Route lanlink-core logs to android.util.Log (logcat) instead of the
        // commonMain println default. The app constructs this factory before any
        // session starts, so the global logger is swapped early enough.
        AndroidLoggerInstaller.install()
    }

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

    override fun createAdvertiser(servicePort: Int, serverDeviceId: String): DiscoveryAdvertiser =
        UdpDiscoveryServer(servicePort, deviceName(), serverDeviceId)

    override fun createScanner(): DiscoveryScanner =
        UdpDiscoveryClient()
}
