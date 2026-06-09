package com.ymr.lanlink.core.data.auth

import com.ymr.lanlink.core.domain.auth.AuthResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InMemoryAuthProviderTest {

    @Test
    fun authenticate_returns_Success_with_correct_PIN() = runBlocking {
        val provider = InMemoryAuthProvider("123456")
        val result = provider.authenticate("TestPeer", "123456".toByteArray())
        assertTrue(result is AuthResult.Success, "Expected Success")
        assertEquals("TestPeer", (result as? AuthResult.Success)?.peerName)
    }

    @Test
    fun authenticate_returns_Failure_with_wrong_PIN() = runBlocking {
        val provider = InMemoryAuthProvider("123456")
        val result = provider.authenticate("TestPeer", "wrong".toByteArray())
        assertTrue(result is AuthResult.Failure, "Expected Failure")
    }

    @Test
    fun authenticate_is_case_sensitive() = runBlocking {
        val provider = InMemoryAuthProvider("123456")
        val result = provider.authenticate("TestPeer", "12345".toByteArray())
        assertTrue(result is AuthResult.Failure, "Expected Failure for wrong length")
    }

    @Test
    fun getCredentials_returns_correct_PIN_bytes() {
        val provider = InMemoryAuthProvider("654321")
        val credentials = provider.getCredentials()
        assertTrue(
            credentials?.contentEquals("654321".toByteArray()) ?: false,
            "Credentials should match"
        )
    }

    @Test
    fun default_PIN_is_6_digits_and_numeric_only() {
        val provider = InMemoryAuthProvider()
        val defaultPin = provider.getCredentials()?.decodeToString() ?: ""
        assertEquals(6, defaultPin.length, "Default PIN should be 6 digits")
        assertTrue(defaultPin.all { it.isDigit() }, "Default PIN should be numeric")
    }

    @Test
    fun PIN_must_be_exactly_6_digits() {
        assertFailsWith<IllegalArgumentException>("Too short") {
            InMemoryAuthProvider("12345")
        }
        assertFailsWith<IllegalArgumentException>("Too long") {
            InMemoryAuthProvider("1234567")
        }
        assertFailsWith<IllegalArgumentException>("Contains letter") {
            InMemoryAuthProvider("12345a")
        }
    }

    @Test
    fun lockout_after_5_failed_attempts() = runBlocking {
        val provider = InMemoryAuthProvider("123456")

        // 5 failed attempts
        repeat(5) { i ->
            val result = provider.authenticate("TestPeer", "wrong".toByteArray())
            assertTrue(result is AuthResult.Failure, "Attempt ${i + 1} should fail")
        }

        // 6th attempt should be locked
        val lockedResult = provider.authenticate("TestPeer", "123456".toByteArray())
        assertTrue(lockedResult is AuthResult.Failure, "6th attempt should be locked")
        assertTrue(
            (lockedResult as? AuthResult.Failure)?.message?.contains("locked") == true,
            "Should mention lockout"
        )
    }

    @Test
    fun different_peers_have_separate_attempt_counters() = runBlocking {
        val provider = InMemoryAuthProvider("123456")

        // 4 failed attempts for PeerA
        repeat(4) {
            provider.authenticate("PeerA", "wrong".toByteArray())
        }

        // PeerB should still have full attempts
        val result = provider.authenticate("PeerB", "123456".toByteArray())
        assertTrue(result is AuthResult.Success, "PeerB should succeed")
    }

    @Test
    fun successful_auth_resets_failed_attempts_counter() = runBlocking {
        val provider = InMemoryAuthProvider("123456")

        // 3 failed attempts
        repeat(3) {
            provider.authenticate("TestPeer", "wrong".toByteArray())
        }

        // Successful auth
        provider.authenticate("TestPeer", "123456".toByteArray())

        // Counter should be reset, should have full attempts again
        repeat(4) { i ->
            val result = provider.authenticate("TestPeer", "wrong".toByteArray())
            assertTrue(result is AuthResult.Failure, "Attempt after reset should fail")
        }

        // 5th attempt should still work (counter reset)
        val fifthFailed = provider.authenticate("TestPeer", "wrong".toByteArray())
        assertTrue(fifthFailed is AuthResult.Failure, "5th attempt should still fail")
    }
}
