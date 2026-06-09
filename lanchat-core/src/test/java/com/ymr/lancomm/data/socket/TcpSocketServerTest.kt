package com.ymr.lancomm.data.socket

import com.ymr.lancomm.data.auth.NoOpAuthProvider
import com.ymr.lancomm.domain.model.ConnectionState
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
    fun `broadcast does nothing when no clients connected`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // Broadcast should not throw even with no clients
        server.broadcast(0, "test".toByteArray())
        server.stop()
    }

    @Test
    fun `send (alias for broadcast) does nothing when no clients connected`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        server.start()
        // Send should not throw even with no clients
        server.send(0, "test".toByteArray())
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
        var receivedCount = 0
        server.messages.collect { receivedCount++ }
        assertEquals(0, receivedCount)
        server.stop()
    }

    @Test
    fun `start can be called only once`() = runTest {
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        val port1 = server.start()
        val port2 = server.start()  // Should not throw, returns same port
        assertEquals(port1, port2)
        server.stop()
    }
}