package com.ymr.lancomm.data.socket

import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.PeerInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class TcpSocketClientTest {

    @Test
    fun `initial state is Idle`() {
        val client = TcpSocketClient()
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun `protobufChannel is null initially`() {
        val client = TcpSocketClient()
        assertNull(client.protobufChannel)
    }

    @Test
    fun `connection state transitions to Connecting when connect is called`() = runTest {
        val client = TcpSocketClient()
        val peer = PeerInfo(
            name = "TestServer",
            host = InetAddress.getLoopbackAddress(),
            port = 12345
        )

        // This will fail but state should be Connecting
        try {
            client.connect(peer)
        } catch (_: Exception) {
            // Expected to fail without a server
        }

        // State should be either Connecting or Error, not Idle
        val state = client.connectionState.value
        assertTrue(
            "State should be Connecting or Error, was: $state",
            state is ConnectionState.Connecting || state is ConnectionState.Error
        )
    }

    @Test
    fun `disconnect resets state to Idle`() = runTest {
        val client = TcpSocketClient()
        val peer = PeerInfo(
            name = "TestServer",
            host = InetAddress.getLoopbackAddress(),
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
    fun `disconnect can be called multiple times safely`() {
        val client = TcpSocketClient()
        client.disconnect()
        client.disconnect()
        client.disconnect()
        // Should not throw
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun `send throws IllegalStateException when not connected`() = runTest {
        val client = TcpSocketClient()
        try {
            client.send("test".toByteArray())
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Not connected") ?: false)
        }
    }

    @Test
    fun `constructor accepts custom timeout values`() {
        val client = TcpSocketClient(
            connectionTimeoutMs = 5_000,
            readTimeoutMs = 20_000,
            heartbeatIntervalMs = 10_000
        )
        assertEquals(ConnectionState.Idle, client.connectionState.value)
    }

    @Test
    fun `messages flow is empty initially`() = runTest {
        val client = TcpSocketClient()
        var receivedCount = 0
        client.messages.collect { receivedCount++ }
        // Should collect nothing, so count stays 0
        assertEquals(0, receivedCount)
    }
}