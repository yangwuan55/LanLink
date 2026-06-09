package com.ymr.lancomm.domain.model

/**
 * Information about a discovered peer on the LAN.
 *
 * @property name Display name of the peer (usually device model)
 * @property host IP address of the peer
 * @property port TCP port where the peer is listening
 */
data class PeerInfo(
    val name: String,
    val host: String,
    val port: Int
)