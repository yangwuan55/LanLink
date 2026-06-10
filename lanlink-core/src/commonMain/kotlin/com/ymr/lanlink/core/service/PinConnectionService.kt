package com.ymr.lanlink.core.service

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class PinConnectionState {
    object Idle : PinConnectionState()
    // pin is nullable: the server listening / token reconnect paths have no PIN.
    // It is non-null only for the client PIN pairing flow (pairWithServer).
    data class Discovering(val pin: String?) : PinConnectionState()
    data class Connecting(val pin: String?, val isServer: Boolean) : PinConnectionState()
    data class Connected(val pin: String?, val isServer: Boolean, val peerName: String) : PinConnectionState()
    data class Error(val pin: String?, val reason: String) : PinConnectionState()
}

sealed class PinConnectionEvent {
    data class PeerConnected(val peerName: String) : PinConnectionEvent()
    object PeerDisconnected : PinConnectionEvent()
    /**
     * Authentication failed. [reason] follows the connection's message; for a
     * revoked/unknown credential during reconnectLastServer it is the literal
     * "PAIRING_REVOKED". The service already cleared the stored credential — the
     * App should fall back to pairWithServer (PIN pairing).
     */
    data class AuthFailed(val reason: String) : PinConnectionEvent()

    /**
     * Emitted after a first PIN pairing (pairWithServer) succeeds: a credential
     * has been minted and persisted via the injected store, ready for
     * reconnectLastServer. Observability only — the blob itself deliberately does
     * not travel on this flow (it contains the shared secret); Apps that need it
     * see it through their injected [PairingCredentialStore][com.ymr.lanlink.core.domain.pairing.PairingCredentialStore].
     */
    object PairingCredentialIssued : PinConnectionEvent()

    /**
     * Emitted after a successful reconnectLastServer when the server's address
     * changed: the refreshed credential has been persisted via the injected
     * store. Observability only; the blob does not travel on this flow.
     */
    object PairingCredentialUpdated : PinConnectionEvent()
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

    /** Whether the server PIN pairing window is currently open (see [startPairing]). */
    val pairingActive: StateFlow<Boolean>

    /**
     * Starts the server: binds the TCP listener and begins UDP advertising. The
     * server immediately accepts token reconnects (authScheme=1) from
     * already-paired clients but does NOT open a PIN pairing window — call
     * [startPairing] to accept new PIN pairings.
     */
    fun startServer()

    /**
     * Opens the server PIN pairing window using [pin]. Must be called after
     * [startServer]; otherwise the service enters [PinConnectionState.Error].
     * While open, incoming PIN handshakes are validated against [pin] and, on
     * success, a pairing credential is minted and shipped to the client.
     */
    fun startPairing(pin: String)

    /**
     * Closes the server PIN pairing window: subsequent PIN handshakes are
     * rejected. Already-connected peers and token reconnects are unaffected.
     */
    fun stopPairing()

    /**
     * Client side: discovers and PIN-pairs with a server using [pin] (first-time
     * pairing). On success a [PinConnectionEvent.PairingCredentialIssued] is
     * emitted and the credential is stored for [reconnectLastServer].
     */
    fun pairWithServer(pin: String)

    /**
     * Client side: reconnects to the most recently paired server using the stored
     * credential blob (auto-saved on pairing). Runs the HMAC challenge-response
     * direct-connect path; no PIN is required. Enters [PinConnectionState.Error]
     * with reason "no stored pairing" when no credential has been stored.
     */
    fun reconnectLastServer()

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
