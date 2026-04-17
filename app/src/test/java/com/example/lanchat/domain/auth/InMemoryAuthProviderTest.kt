package com.example.lanchat.domain.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InMemoryAuthProviderTest {
    @Test
    fun `InMemoryAuthProvider returns Success with correct PIN`() = runBlocking {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider("1234")
        val credentials = "1234".toByteArray()
        val result = provider.authenticate("TestPeer", credentials)
        assertTrue(result is AuthResult.Success)
    }

    @Test
    fun `InMemoryAuthProvider returns Failure with wrong PIN`() = runBlocking {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider("1234")
        val credentials = "wrong".toByteArray()
        val result = provider.authenticate("TestPeer", credentials)
        assertTrue(result is AuthResult.Failure)
        assertEquals("Invalid PIN", (result as AuthResult.Failure).message)
    }

    @Test
    fun `InMemoryAuthProvider default PIN is 1234`() {
        val provider = com.example.lanchat.data.auth.InMemoryAuthProvider()
        assertTrue(provider.getCredentials()?.contentEquals("1234".toByteArray()) ?: false)
    }
}