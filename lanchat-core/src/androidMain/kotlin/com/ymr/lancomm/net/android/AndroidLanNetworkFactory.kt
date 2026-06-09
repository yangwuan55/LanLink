package com.ymr.lancomm.net.android

import com.ymr.lancomm.data.auth.InMemoryAuthProvider
import com.ymr.lancomm.data.discovery.UdpDiscoveryClient
import com.ymr.lancomm.data.discovery.UdpDiscoveryServer
import com.ymr.lancomm.data.socket.TcpSocketClient
import com.ymr.lancomm.data.socket.TcpSocketServer
import com.ymr.lancomm.net.DiscoveryAdvertiser
import com.ymr.lancomm.net.DiscoveryScanner
import com.ymr.lancomm.net.LanClient
import com.ymr.lancomm.net.LanNetworkFactory
import com.ymr.lancomm.net.LanServer
import com.ymr.lancomm.platform.AndroidLoggerInstaller
import com.ymr.lancomm.platform.deviceName

/**
 * Android implementation of [LanNetworkFactory]: wires the TCP socket transport
 * and UDP discovery components behind the common interfaces.
 */
class AndroidLanNetworkFactory : LanNetworkFactory {

    init {
        // Route lanchat-core logs to android.util.Log (logcat) instead of the
        // commonMain println default. The app constructs this factory before any
        // session starts, so the global logger is swapped early enough.
        AndroidLoggerInstaller.install()
    }

    override fun createServer(pin: String): LanServer =
        TcpSocketServer(port = 0, authProvider = InMemoryAuthProvider(pin))

    override fun createClient(): LanClient =
        TcpSocketClient()

    override fun createAdvertiser(servicePort: Int): DiscoveryAdvertiser =
        UdpDiscoveryServer(servicePort, deviceName())

    override fun createScanner(): DiscoveryScanner =
        UdpDiscoveryClient()
}
