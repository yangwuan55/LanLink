package com.ymr.lanlink.core.data.socket

import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TcpSocketClientTest {

    @Test
    fun initial_state_is_Idle() {
        val client = TcpSocketClient()
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun isConnected_is_false_initially() {
        val client = TcpSocketClient()
        assertFalse(client.isConnected)
    }

    @Test
    fun connection_state_transitions_to_Connecting_when_connect_is_called() = runBlocking {
        val client = TcpSocketClient(connectionTimeoutMs = 1_000)
        val peer = PeerInfo(
            name = "TestServer",
            host = "127.0.0.1",
            port = 12345
        )

        // This will fail but state should be Connecting or Error
        try {
            client.connect(peer)
        } catch (_: Exception) {
            // Expected to fail without a server
        }

        // State should be either Connecting or Error, not Idle
        val state = client.connectionState.value
        assertTrue(
            state is ConnectionState.Connecting || state is ConnectionState.Error,
            "State should be Connecting or Error, was: $state"
        )
    }

    @Test
    fun disconnect_resets_state_to_Idle() = runBlocking {
        val client = TcpSocketClient(connectionTimeoutMs = 1_000)
        val peer = PeerInfo(
            name = "TestServer",
            host = "127.0.0.1",
            port = 12345
        )

        try {
            client.connect(peer)
        } catch (_: Exception) {
            // Expected to fail
        }

        client.disconnect()
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun disconnect_can_be_called_multiple_times_safely() {
        val client = TcpSocketClient()
        client.disconnect()
        client.disconnect()
        client.disconnect()
        // Should not throw
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun send_throws_IllegalStateException_when_not_connected() = runBlocking {
        val client = TcpSocketClient()
        try {
            client.send(0, "test".toByteArray())
            error("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Not connected") ?: false)
        }
    }

    @Test
    fun constructor_accepts_custom_timeout_values() {
        val client = TcpSocketClient(
            connectionTimeoutMs = 5_000,
            readTimeoutMs = 20_000,
            heartbeatIntervalMs = 10_000
        )
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun messages_flow_is_empty_initially() = runBlocking {
        val client = TcpSocketClient()
        // messages is a hot SharedFlow that never completes; collect with a
        // short timeout and assert nothing was emitted before it elapses.
        var receivedCount = 0
        withTimeoutOrNull(200) {
            client.messages.collect { receivedCount++ }
        }
        assertEquals(0, receivedCount)
    }
}
