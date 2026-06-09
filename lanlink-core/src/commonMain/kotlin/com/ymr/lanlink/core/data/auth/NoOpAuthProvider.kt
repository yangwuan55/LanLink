package com.ymr.lanlink.core.data.auth

import com.ymr.lanlink.core.domain.auth.AuthProvider
import com.ymr.lanlink.core.domain.auth.AuthResult
import kotlinx.coroutines.delay

class NoOpAuthProvider : AuthProvider {
    override suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult {
        delay(100) // Simulate async auth
        return AuthResult.Success(peerName)
    }

    override fun getCredentials(): ByteArray? = null
}