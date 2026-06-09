package com.ymr.lancomm.domain.auth

sealed class AuthResult {
    data class Success(val peerName: String) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}