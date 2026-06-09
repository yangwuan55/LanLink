package com.ymr.lancomm.net

import com.ymr.lancomm.domain.model.PeerInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Server-side LAN transport. Listens for inbound connections, performs the auth
 * handshake, and exposes inbound frames and peer lifecycle as flows.
 *
 * Each inbound frame is a wire `type` tag paired with its raw payload bytes.
 */
interface LanServer {
    /** Inbound frames as (wire-type, payload-bytes) pairs. */
    val messages: SharedFlow<Pair<Int, ByteArray>>

    /** Emits a peer once it has completed the auth handshake. */
    val authenticatedPeers: SharedFlow<PeerInfo>

    /** Current set of connected peers. */
    val connectedPeers: StateFlow<List<PeerInfo>>

    /** Binds the listening socket and starts accepting clients; returns the bound port. */
    suspend fun start(): Int

    /** Sends [data] tagged with wire [type] to connected peer(s). */
    suspend fun send(type: Int, data: ByteArray)

    /** Stops the server and releases all resources. */
    fun stop()
}
