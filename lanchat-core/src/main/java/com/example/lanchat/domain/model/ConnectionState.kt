package com.example.lanchat.domain.model

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Discovering : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val peerName: String, val isServer: Boolean) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}