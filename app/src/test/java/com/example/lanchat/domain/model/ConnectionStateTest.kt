package com.example.lanchat.domain.model

import org.junit.Assert.*
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun `ConnectionState Idle is correct`() {
        val state = ConnectionState.Idle
        assertTrue(state is ConnectionState.Idle)
    }

    @Test
    fun `ConnectionState Connected has peerName and isServer`() {
        val state = ConnectionState.Connected("DeviceA", isServer = true)
        assertEquals("DeviceA", state.peerName)
        assertTrue(state.isServer)
    }

    @Test
    fun `ConnectionState Error has message`() {
        val state = ConnectionState.Error("Connection failed")
        assertEquals("Connection failed", state.message)
    }

    @Test
    fun `ConnectionState Discovering is correct`() {
        val state = ConnectionState.Discovering
        assertTrue(state is ConnectionState.Discovering)
    }
}