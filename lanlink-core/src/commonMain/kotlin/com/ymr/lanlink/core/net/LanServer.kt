package com.ymr.lanlink.core.net

import com.ymr.lanlink.core.domain.model.PeerInfo
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

    /**
     * Opens or closes the PIN pairing window. A non-null [pin] opens the window:
     * incoming PIN handshakes (authScheme=0) are validated against it and, on
     * success, mint + ship a pairing credential. A null [pin] closes the window:
     * every PIN handshake is rejected with a failure [AuthResponse]. The token
     * reconnect path (authScheme=1) is unaffected and always available. Each call
     * with a non-null [pin] rebuilds the underlying auth provider so brute-force
     * lockout state does not carry across pairing windows.
     */
    fun setPairingPin(pin: String?)

    /** Sends [data] tagged with wire [type] to connected peer(s). */
    suspend fun send(type: Int, data: ByteArray)

    /** Stops the server and releases all resources. */
    fun stop()
}
