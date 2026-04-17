package com.example.lanchat.domain.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NoOpAuthProviderTest {
    @Test
    fun `NoOpAuthProvider always returns Success`() = runBlocking {
        val provider = com.example.lanchat.data.auth.NoOpAuthProvider()
        val result = provider.authenticate("TestPeer", null)
        assertTrue(result is AuthResult.Success)
        assertEquals("TestPeer", (result as AuthResult.Success).peerName)
    }

    @Test
    fun `NoOpAuthProvider getCredentials returns null`() {
        val provider = com.example.lanchat.data.auth.NoOpAuthProvider()
        assertNull(provider.getCredentials())
    }
}