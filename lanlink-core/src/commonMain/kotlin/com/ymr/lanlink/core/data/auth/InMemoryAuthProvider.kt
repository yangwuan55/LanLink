package com.ymr.lanlink.core.data.auth

import com.ymr.lanlink.core.domain.auth.AuthProvider
import com.ymr.lanlink.core.domain.auth.AuthResult
import com.ymr.lanlink.core.platform.nowMillis
import com.ymr.lanlink.core.platform.secureRandomBytes
import kotlinx.coroutines.delay

/**
 * In-memory auth provider with configurable PIN.
 * Default PIN is randomly generated at first use and can be changed.
 *
 * SECURITY NOTE: PIN is transmitted in plain text. For production use,
 * implement TLS/SSL encryption or use a stronger authentication mechanism.
 */
class InMemoryAuthProvider(
    expectedPin: String = generateSecureDefaultPin()
) : AuthProvider {
    private val expectedPin: String
    private var failedAttempts = mutableMapOf<String, Int>()
    private val maxAttempts = 5
    private val lockoutDurationMs = 30_000L
    private val lockoutMap = mutableMapOf<String, Long>()

    init {
        // Validate PIN format (6 digits)
        require(expectedPin.length == 6 && expectedPin.all { it.isDigit() }) {
            "PIN must be exactly 6 digits"
        }
        this.expectedPin = expectedPin
    }

    override suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult {
        // Check lockout
        synchronized(lockoutMap) {
            lockoutMap[peerName]?.let { lockoutEnd ->
                if (nowMillis() < lockoutEnd) {
                    val remainingMs = lockoutEnd - nowMillis()
                    return AuthResult.Failure("Account locked. Try again in ${remainingMs / 1000}s")
                }
                lockoutMap.remove(peerName)
                failedAttempts.remove(peerName)
            }
        }

        delay(100)

        val pin = credentials?.decodeToString()
        return if (pin == expectedPin) {
            // Reset failed attempts on success
            synchronized(failedAttempts) {
                failedAttempts.remove(peerName)
            }
            AuthResult.Success(peerName)
        } else {
            // Track failed attempts
            val attempts: Int
            synchronized(failedAttempts) {
                attempts = (failedAttempts[peerName] ?: 0) + 1
                failedAttempts[peerName] = attempts
            }

            if (attempts >= maxAttempts) {
                // Lock out the peer
                synchronized(lockoutMap) {
                    lockoutMap[peerName] = nowMillis() + lockoutDurationMs
                }
                synchronized(failedAttempts) {
                    failedAttempts.remove(peerName)
                }
                AuthResult.Failure("Too many failed attempts. Locked for ${lockoutDurationMs / 1000}s")
            } else {
                AuthResult.Failure("Invalid PIN (${maxAttempts - attempts} attempts remaining)")
            }
        }
    }

    override fun getCredentials(): ByteArray? = expectedPin.toByteArray()

    companion object {
        private fun generateSecureDefaultPin(): String {
            val bytes = secureRandomBytes(4)
            // Generate 6-digit PIN from random bytes
            val num = (bytes[0].toInt() and 0xFF shl 24) or
                    (bytes[1].toInt() and 0xFF shl 16) or
                    (bytes[2].toInt() and 0xFF shl 8) or
                    (bytes[3].toInt() and 0xFF)
            // Mask off the sign bit so the modulo can't go negative (which would
            // otherwise yield a non-6-digit or negative PIN).
            val pin = ((num and 0x7FFFFFFF) % 900000) + 100000  // Range: 100000-999999
            return pin.toString()
        }
    }
}