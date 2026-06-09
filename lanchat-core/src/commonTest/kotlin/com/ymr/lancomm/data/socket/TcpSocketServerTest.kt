package com.ymr.lancomm.data.socket

import com.ymr.lancomm.data.auth.NoOpAuthProvider
import com.ymr.lancomm.domain.model.ConnectionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpSocketServerTest {

    @Test
    fun initial_state_is_Idle() {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun connectedPeers_is_empty_initially() {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        assertTrue(server.connectedPeers.value.isEmpty())
    }

    @Test
    fun start_returns_a_valid_port_number() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        val port = server.start()
        assertTrue(port > 0, "Port should be positive")
        assertTrue(port < 65536, "Port should be less than 65536")
        server.stop()
    }

    @Test
    fun state_transitions_to_Connected_after_start() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        val state = server.connectionState.value
        assertTrue(
            state is ConnectionState.Connected,
            "State should be Connected, was: $state"
        )
        server.stop()
    }

    @Test
    fun stop_resets_state_to_Idle() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        server.stop()
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun stop_can_be_called_multiple_times_safely() {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.stop()
        server.stop()
        server.stop()
        // Should not throw
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun broadcast_throws_when_no_clients_connected() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // Current behavior: broadcast with no clients throws IllegalStateException.
        try {
            server.broadcast(0, "test".toByteArray())
            error("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No clients connected") ?: false)
        }
        server.stop()
    }

    @Test
    fun send_alias_for_broadcast_throws_when_no_clients_connected() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // send() delegates to broadcast(), which throws with no clients.
        try {
            server.send(0, "test".toByteArray())
            error("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No clients connected") ?: false)
        }
        server.stop()
    }

    @Test
    fun sendToClient_does_nothing_when_client_not_found() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // Should not throw for non-existent client
        server.sendToClient("non-existent-client", 0, "test".toByteArray())
        server.stop()
    }

    @Test
    fun constructor_accepts_custom_timeout_values() {
        val server = TcpSocketServer(
            authProvider = NoOpAuthProvider(),
            connectionTimeoutMs = 60_000,
            heartbeatIntervalMs = 30_000
        )
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun messages_flow_is_empty_initially() = runBlocking {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // messages is a hot SharedFlow that never completes; collect with a
        // short timeout and assert nothing was emitted before it elapses.
        var receivedCount = 0
        withTimeoutOrNull(200) {
            server.messages.collect { receivedCount++ }
        }
        assertEquals(0, receivedCount)
        server.stop()
    }

    @Test
    fun start_binds_a_new_socket_each_call() = runBlocking {
        // Current behavior: there is no double-start guard, so each start()
        // binds a fresh ServerSocket and returns a (different) ephemeral port.
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        val port1 = server.start()
        val port2 = server.start()
        assertTrue(port1 > 0)
        assertTrue(port2 > 0)
        server.stop()
    }
}
