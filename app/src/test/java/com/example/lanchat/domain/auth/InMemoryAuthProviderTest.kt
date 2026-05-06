package com.example.lanchat.domain.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InMemoryAuthProviderTest {

    @Test
    fun `InMemoryAuthProvider returns Success with correct 6-digit PIN`() = runBlocking {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider("123456")
        val credentials = "123456".toByteArray()
        val result = provider.authenticate("TestPeer", credentials)
        assertTrue(result is com.ymr.lancomm.domain.auth.AuthResult.Success)
    }

    @Test
    fun `InMemoryAuthProvider returns Failure with wrong PIN`() = runBlocking {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider("654321")
        val credentials = "wrong".toByteArray()
        val result = provider.authenticate("TestPeer", credentials)
        assertTrue(result is com.ymr.lancomm.domain.auth.AuthResult.Failure)
    }

    @Test
    fun `InMemoryAuthProvider default PIN is 6 digits`() {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider()
        val defaultPin = provider.getCredentials()?.decodeToString() ?: ""
        assertEquals("Default PIN should be 6 digits", 6, defaultPin.length)
        assertTrue("Default PIN should be numeric", defaultPin.all { it.isDigit() })
    }

    @Test
    fun `PIN must be exactly 6 digits`() {
        assertThrows(IllegalArgumentException::class.java) {
            com.example.lanchat.data.auth.InMemoryAuthProvider("12345")  // Too short
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.example.lanchat.data.auth.InMemoryAuthProvider("1234567")  // Too long
        }
    }

    @Test
    fun `getCredentials returns correct PIN bytes`() {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider("999888")
        val credentials = provider.getCredentials()
        assertTrue("Credentials should match",
            credentials?.contentEquals("999888".toByteArray()) ?: false)
    }

    @Test
    fun `lockout after 5 failed attempts`() = runBlocking {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider("123456")

        // 5 failed attempts
        repeat(5) {
            val result = provider.authenticate("TestPeer", "wrong".toByteArray())
            assertTrue("Attempt ${it + 1} should fail",
                result is com.ymr.lancomm.domain.auth.AuthResult.Failure)
        }

        // 6th attempt should be locked
        val lockedResult = provider.authenticate("TestPeer", "123456".toByteArray())
        assertTrue("6th attempt should be locked",
            lockedResult is com.ymr.lancomm.domain.auth.AuthResult.Failure)
    }
}