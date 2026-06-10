package com.ymr.lanlink.core.domain.auth

sealed class AuthResult {
    /**
     * [responseData] is an optional payload returned to the client in
     * `AuthResponse.customData` (e.g. the minted pairing record on first PIN
     * handshake). Null/empty for plain PIN auth with no issuance.
     */
    data class Success(val peerName: String, val responseData: ByteArray? = null) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}