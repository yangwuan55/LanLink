package com.ymr.lancomm.data.auth

import com.ymr.lancomm.domain.auth.AuthProvider
import com.ymr.lancomm.domain.auth.AuthResult
import kotlinx.coroutines.delay

class InMemoryAuthProvider(private val expectedPin: String = "1234") : AuthProvider {
    override suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult {
        delay(100)
        val pin = credentials?.decodeToString()
        return if (pin == expectedPin) {
            AuthResult.Success(peerName)
        } else {
            AuthResult.Failure("Invalid PIN")
        }
    }

    override fun getCredentials(): ByteArray? = expectedPin.toByteArray()
}