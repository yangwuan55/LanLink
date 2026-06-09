package com.ymr.lancomm.net

import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Client-side LAN transport. Connects to a [LanServer], performs the auth
 * handshake, then streams inbound frames.
 *
 * Each inbound frame is a wire `type` tag paired with its raw payload bytes.
 */
interface LanClient {
    /** Inbound frames as (wire-type, payload-bytes) pairs. */
    val messages: SharedFlow<Pair<Int, ByteArray>>

    /** Current connection state. */
    val connectionState: StateFlow<ConnectionState>

    /** Opens the connection to [peer]. */
    suspend fun connect(peer: PeerInfo)

    /**
     * Runs the auth handshake in domain terms: sends [deviceName] + [credentials],
     * receives the server's verdict, and maps it to an [AuthHandshakeResult].
     */
    suspend fun authenticate(deviceName: String, credentials: ByteArray): AuthHandshakeResult

    /** Starts the inbound read loop (and heartbeat) after a successful handshake. */
    fun startReadLoop()

    /** Sends [data] tagged with wire [type] to the server. */
    suspend fun send(type: Int, data: ByteArray)

    /** Closes the connection and releases all resources. */
    fun disconnect()
}
