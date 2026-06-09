package com.ymr.lancomm.net

/**
 * Advertises this device's presence on the LAN (e.g. via UDP broadcast) so peers
 * can discover it. The service port and device name are supplied by the
 * [LanNetworkFactory] when the advertiser is created.
 */
interface DiscoveryAdvertiser {
    /** Begins advertising. */
    fun start()

    /** Stops advertising and releases resources. */
    fun stop()
}
