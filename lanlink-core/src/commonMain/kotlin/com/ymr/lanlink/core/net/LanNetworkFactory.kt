package com.ymr.lanlink.core.net

/**
 * Platform seam: constructs the concrete LAN transport + discovery components.
 * A future platform is added purely by supplying a new implementation of this
 * factory; [commonMain] orchestration depends only on the interfaces above.
 */
interface LanNetworkFactory {
    /** Creates a server that authenticates clients against the 6-digit [pin]. */
    fun createServer(pin: String): LanServer

    /** Creates a client transport. */
    fun createClient(): LanClient

    /** Creates an advertiser broadcasting the given [servicePort]. */
    fun createAdvertiser(servicePort: Int): DiscoveryAdvertiser

    /** Creates a scanner that listens for advertised peers. */
    fun createScanner(): DiscoveryScanner
}
