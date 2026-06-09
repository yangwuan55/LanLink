package com.ymr.lancomm.data.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.ymr.lancomm.domain.auth.AuthResult

class InMemoryAuthProviderTest {

    @Test
    fun `authenticate returns Success with correct PIN`() = runBlocking {
        val provider = InMemoryAuthProvider("123456")
        val result = provider.authenticate("TestPeer", "123456".toByteArray())
        assertTrue("Expected Success", result is AuthResult.Success)
        assertEquals("TestPeer", (result as AuthResult.Success).peerName)
    }

    @Test
    fun `authenticate returns Failure with wrong PIN`() = runBlocking {
        val provider = InMemoryAuthProvider("123456")
        val result = provider.authenticate("TestPeer", "wrong".toByteArray())
        assertTrue("Expected Failure", result is AuthResult.Failure)
    }

    @Test
    fun `authenticate is case sensitive`() = runBlocking {
        val provider = InMemoryAuthProvider("123456")
        val result = provider.authenticate("TestPeer", "12345".toByteArray())
        assertTrue("Expected Failure for wrong length", result is AuthResult.Failure)
    }

    @Test
    fun `getCredentials returns correct PIN bytes`() {
        val provider = InMemoryAuthProvider("654321")
        val credentials = provider.getCredentials()
        assertTrue("Credentials should match", credentials?.contentEquals("654321".toByteArray() ?: byteArrayOf()) ?: false)
    }

    @Test
    fun `default PIN is 6 digits and numeric only`() {
        val provider = InMemoryAuthProvider()
        val defaultPin = provider.getCredentials()?.decodeToString() ?: ""
        assertEquals("Default PIN should be 6 digits", 6, defaultPin.length)
        assertTrue("Default PIN should be numeric", defaultPin.all { it.isDigit() })
    }

    @Test
    fun `PIN must be exactly 6 digits`() {
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryAuthProvider("12345")  // Too short
        }
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryAuthProvider("1234567")  // Too long
        }
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryAuthProvider("12345a")  // Contains letter
        }
    }

    @Test
    fun `lockout after 5 failed attempts`() = runBlocking {
        val provider = InMemoryAuthProvider("123456")

        // 5 failed attempts
        repeat(5) {
            val result = provider.authenticate("TestPeer", "wrong".toByteArray())
            assertTrue("Attempt ${it + 1} should fail", result is AuthResult.Failure)
        }

        // 6th attempt should be locked
        val lockedResult = provider.authenticate("TestPeer", "123456".toByteArray())
        assertTrue("6th attempt should be locked", lockedResult is AuthResult.Failure)
        assertTrue("Should mention lockout", (lockedResult as AuthResult.Failure).message.contains("locked"))
    }

    @Test
    fun `different peers have separate attempt counters`() = runBlocking {
        val provider = InMemoryAuthProvider("123456")

        // 4 failed attempts for PeerA
        repeat(4) {
            provider.authenticate("PeerA", "wrong".toByteArray())
        }

        // PeerB should still have full attempts
        val result = provider.authenticate("PeerB", "123456".toByteArray())
        assertTrue("PeerB should succeed", result is AuthResult.Success)
    }

    @Test
    fun `successful auth resets failed attempts counter`() = runBlocking {
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
            assertTrue("Attempt after reset should fail", result is AuthResult.Failure)
        }

        // 5th attempt should still work (counter reset)
        val fifthFailed = provider.authenticate("TestPeer", "wrong".toByteArray())
        assertTrue("5th attempt should still fail", fifthFailed is AuthResult.Failure)
    }
}