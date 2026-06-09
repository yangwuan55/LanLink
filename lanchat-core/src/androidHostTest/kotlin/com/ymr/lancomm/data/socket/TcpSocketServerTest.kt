package com.ymr.lancomm.data.socket

import com.ymr.lancomm.data.auth.NoOpAuthProvider
import com.ymr.lancomm.domain.model.ConnectionState
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TcpSocketServerTest {

    @Test
    fun `initial state is Idle`() {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun `connectedPeers is empty initially`() {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        assertTrue(server.connectedPeers.value.isEmpty())
    }

    @Test
    fun `start returns a valid port number`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        val port = server.start()
        assertTrue("Port should be positive", port > 0)
        assertTrue("Port should be less than 65536", port < 65536)
        server.stop()
    }

    @Test
    fun `state transitions to Connected after start`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        val state = server.connectionState.value
        assertTrue(
            "State should be Connected, was: $state",
            state is ConnectionState.Connected
        )
        server.stop()
    }

    @Test
    fun `stop resets state to Idle`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        server.stop()
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun `stop can be called multiple times safely`() {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.stop()
        server.stop()
        server.stop()
        // Should not throw
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun `broadcast throws when no clients connected`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // Current behavior: broadcast with no clients throws IllegalStateException.
        try {
            server.broadcast(0, "test".toByteArray())
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No clients connected") ?: false)
        }
        server.stop()
    }

    @Test
    fun `send (alias for broadcast) throws when no clients connected`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // send() delegates to broadcast(), which throws with no clients.
        try {
            server.send(0, "test".toByteArray())
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No clients connected") ?: false)
        }
        server.stop()
    }

    @Test
    fun `sendToClient does nothing when client not found`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // Should not throw for non-existent client
        server.sendToClient("non-existent-client", 0, "test".toByteArray())
        server.stop()
    }

    @Test
    fun `constructor accepts custom timeout values`() {
        val server = TcpSocketServer(
            authProvider = NoOpAuthProvider(),
            connectionTimeoutMs = 60_000,
            heartbeatIntervalMs = 30_000
        )
        assertEquals(ConnectionState.Idle, server.connectionState.value)
    }

    @Test
    fun `messages flow is empty initially`() = runTest {
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
    fun `start binds a new socket each call`() = runTest {
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