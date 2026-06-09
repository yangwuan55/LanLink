package com.example.lanchat.domain.auth

import com.ymr.lancomm.domain.auth.AuthResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NoOpAuthProviderTest {
    @Test
    fun `NoOpAuthProvider always returns Success`() = runBlocking {
        val provider = com.ymr.lancomm.data.auth.NoOpAuthProvider()
        val result = provider.authenticate("TestPeer", null)
        assertTrue(result is AuthResult.Success)
        assertEquals("TestPeer", (result as AuthResult.Success).peerName)
    }

    @Test
    fun `NoOpAuthProvider getCredentials returns null`() {
        val provider = com.ymr.lancomm.data.auth.NoOpAuthProvider()
        assertNull(provider.getCredentials())
    }
}