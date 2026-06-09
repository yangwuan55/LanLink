package com.ymr.lanlink.core.service

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class PinConnectionState {
    object Idle : PinConnectionState()
    data class Discovering(val pin: String) : PinConnectionState()
    data class Connecting(val pin: String, val isServer: Boolean) : PinConnectionState()
    data class Connected(val pin: String, val isServer: Boolean, val peerName: String) : PinConnectionState()
    data class Error(val pin: String?, val reason: String) : PinConnectionState()
}

sealed class PinConnectionEvent {
    data class PeerConnected(val peerName: String) : PinConnectionEvent()
    object PeerDisconnected : PinConnectionEvent()
    data class AuthFailed(val reason: String) : PinConnectionEvent()
}

interface PinConnectionService {
    val connectionState: StateFlow<PinConnectionState>

    /**
     * Inbound frames as [TypedMessage]s — a wire [type][TypedMessage.type] tag plus raw
     * [payload][TypedMessage.payload] bytes. The caller decides how to parse the bytes per type;
     * the service stays payload-agnostic so it can carry any number of message kinds.
     */
    val messageFlow: SharedFlow<TypedMessage>
    val eventFlow: SharedFlow<PinConnectionEvent>

    /**
     * Start a server listening for incoming connections
     */
    fun startServer(pin: String)

    /**
     * Connect to a server listening for incoming connections
     */
    fun connectServer(pin: String)
    fun disconnect()

    /**
     * Sends [data] to the peer tagged with [type]. The [type] travels on the wire so the receiver
     * can decide how to interpret the bytes — send different [type] values to exchange multiple
     * kinds of message.
     */
    suspend fun send(type: Int, data: ByteArray)
}

/** A frame as carried on the wire: a [type] tag plus its raw [payload] bytes. */
data class TypedMessage(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TypedMessage) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}
