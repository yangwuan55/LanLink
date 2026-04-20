package com.example.lanchat.domain.auth

interface AuthProvider {
    suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult
    fun getCredentials(): ByteArray?
}