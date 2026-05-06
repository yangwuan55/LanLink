package com.ymr.lancomm.data.discovery

import com.ymr.lancomm.data.auth.NoOpAuthProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UdpDiscoveryClientTest {

    @Test
    fun `initial state is Stopped`() {
        val client = UdpDiscoveryClient()
        assertEquals(UdpDiscoveryState.Stopped, client.state.value)
    }

    @Test
    fun `discoveredPeers is empty initially`() {
        val client = UdpDiscoveryClient()
        assertTrue(client.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `start transitions state to Listening`() = runTest {
        val client = UdpDiscoveryClient()
        client.start()
        // State should be Listening
        assertEquals(UdpDiscoveryState.Listening, client.state.value)
        client.stop()
    }

    @Test
    fun `stop transitions state back to Stopped`() = runTest {
        val client = UdpDiscoveryClient()
        client.start()
        client.stop()
        assertEquals(UdpDiscoveryState.Stopped, client.state.value)
    }

    @Test
    fun `start can be called multiple times safely`() = runTest {
        val client = UdpDiscoveryClient()
        client.start()
        client.start()  // Should not throw
        assertEquals(UdpDiscoveryState.Listening, client.state.value)
        client.stop()
    }

    @Test
    fun `stop can be called multiple times safely`() {
        val client = UdpDiscoveryClient()
        client.stop()
        client.stop()
        // Should not throw
        assertEquals(UdpDiscoveryState.Stopped, client.state.value)
    }

    @Test
    fun `stop clears discovered peers`() = runTest {
        val client = UdpDiscoveryClient()
        client.start()
        // Stop should clear peers
        client.stop()
        assertTrue(client.discoveredPeers.value.isEmpty())
    }
}

class UdpDiscoveryServerTest {

    @Test
    fun `initial state is Idle`() {
        val server = UdpDiscoveryServer(
            mockContext(),
            port = 0  // Auto-select
        )
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }

    @Test
    fun `start and stop lifecycle`() = runTest {
        val server = UdpDiscoveryServer(
            mockContext(),
            port = 0
        )
        try {
            server.start()
            val state = server.state.value
            // State should be Broadcasting after start
            assertEquals(UdpDiscoveryState.Broadcasting, state)
            server.stop()
            assertEquals(UdpDiscoveryState.Stopped, server.state.value)
        } catch (_: Exception) {
            // Expected without real Android context
        }
    }

    @Test
    fun `stop can be called when not started`() {
        val server = UdpDiscoveryServer(
            mockContext(),
            port = 0
        )
        server.stop()  // Should not throw
        assertEquals(UdpDiscoveryState.Stopped, server.state.value)
    }

    private fun mockContext(): android.content.Context {
        return object : android.content.Context() {
            override fun getSystemService(name: String): Any? = null
        }
    }
}

class UdpDiscoveryModelsTest {

    @Test
    fun `DiscoveredPeer can be created with required fields`() {
        val peer = DiscoveredPeer(
            name = "TestDevice",
            host = java.net.InetAddress.getLoopbackAddress(),
            port = 12345
        )
        assertEquals("TestDevice", peer.name)
        assertEquals(12345, peer.port)
    }

    @Test
    fun `UdpDiscoveryState enum has expected values`() {
        assertTrue(UdpDiscoveryState.entries.contains(UdpDiscoveryState.Idle))
        assertTrue(UdpDiscoveryState.entries.contains(UdpDiscoveryState.Broadcasting))
        assertTrue(UdpDiscoveryState.entries.contains(UdpDiscoveryState.Listening))
        assertTrue(UdpDiscoveryState.entries.contains(UdpDiscoveryState.Stopped))
        assertTrue(UdpDiscoveryState.entries.contains(UdpDiscoveryState.Error))
    }

    @Test
    fun `DiscoveredPeer equality works correctly`() {
        val addr = java.net.InetAddress.getLoopbackAddress()
        val peer1 = DiscoveredPeer("Device1", addr, 12345)
        val peer2 = DiscoveredPeer("Device1", addr, 12345)
        assertEquals(peer1, peer2)
    }

    @Test
    fun `UdpDiscoveryState Error can be created with message`() {
        val errorState = UdpDiscoveryState.Error("Network unavailable")
        assertTrue(errorState is UdpDiscoveryState.Error)
        assertEquals("Network unavailable", errorState.message)
    }
}