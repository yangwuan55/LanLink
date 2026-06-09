package com.ymr.lanlink.core.domain.model

/**
 * Represents the current state of a LAN connection.
 */
sealed class ConnectionState {
    /** Not connected, idle state */
    object Idle : ConnectionState()
    /** Actively discovering peers */
    object Discovering : ConnectionState()
    /** Attempting to connect */
    object Connecting : ConnectionState()
    /** Successfully connected
     * @property peerName Name of the connected peer
     * @property isServer True if acting as server
     */
    data class Connected(val peerName: String, val isServer: Boolean) : ConnectionState()
    /** Connection error
     * @property message Error description
     */
    data class Error(val message: String) : ConnectionState()
}