package com.ymr.lancomm.domain.auth

/**
 * Interface for implementing custom authentication logic.
 * When a peer connects, you can verify their identity and credentials.
 */
interface AuthProvider {
    suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult
    fun getCredentials(): ByteArray?
}