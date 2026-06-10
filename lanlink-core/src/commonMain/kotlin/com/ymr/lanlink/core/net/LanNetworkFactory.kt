package com.ymr.lanlink.core.net

/**
 * Platform seam: constructs the concrete LAN transport + discovery components.
 * A future platform is added purely by supplying a new implementation of this
 * factory; [commonMain] orchestration depends only on the interfaces above.
 */
interface LanNetworkFactory {
    /**
     * Creates a server with the PIN pairing window initially closed. The server
     * always accepts token (authScheme=1) reconnects from already-paired clients
     * against [pairingRegistry]; PIN pairing (authScheme=0) is rejected until the
     * caller opens a window via [LanServer.setPairingPin]. On a successful PIN
     * handshake the server mints a pairing record into the registry and ships it
     * to the client. [serverDeviceId]/[serverName] are embedded in the issued
     * credential for the client to display/match.
     */
    fun createServer(
        pairingRegistry: com.ymr.lanlink.core.domain.pairing.PairingRegistry,
        serverDeviceId: String = "",
        serverName: String = "",
    ): LanServer

    /** Creates a client transport. */
    fun createClient(): LanClient

    /** Creates an advertiser broadcasting the given [servicePort]. */
    fun createAdvertiser(servicePort: Int): DiscoveryAdvertiser

    /** Creates a scanner that listens for advertised peers. */
    fun createScanner(): DiscoveryScanner
}
