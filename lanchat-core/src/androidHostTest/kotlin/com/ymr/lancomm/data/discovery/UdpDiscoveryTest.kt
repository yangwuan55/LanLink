package com.ymr.lancomm.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

/** Waits (real time) for [predicate] to hold, since start() transitions state on a background Dispatchers.IO scope. */
private fun awaitState(timeoutMs: Long = 3_000, predicate: () -> Boolean): Boolean = runBlocking {
    withTimeoutOrNull(timeoutMs) {
        while (!predicate()) delay(20)
        true
    } ?: false
}

class UdpDiscoveryClientTest {

    @Test
    fun `initial state is Idle`() {
        // The implementation initializes _state to Idle (it only becomes
        // Stopped after stop()); pin that actual behavior.
        val client = UdpDiscoveryClient()
        assertEquals(UdpDiscoveryState.Idle, client.state.value)
    }

    @Test
    fun `discoveredPeers is empty initially`() {
        val client = UdpDiscoveryClient()
        assertTrue(client.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `start transitions state to Listening`() {
        val client = UdpDiscoveryClient()
        client.start()
        // start() binds the socket + flips state on a background coroutine.
        assertTrue(
            "state should reach Listening",
            awaitState { client.state.value == UdpDiscoveryState.Listening }
        )
        client.stop()
    }

    @Test
    fun `stop transitions state back to Stopped`() {
        val client = UdpDiscoveryClient()
        client.start()
        client.stop()
        assertEquals(UdpDiscoveryState.Stopped, client.state.value)
    }

    @Test
    fun `start can be called multiple times safely`() {
        val client = UdpDiscoveryClient()
        client.start()
        client.start()  // Should not throw
        assertTrue(
            "state should reach Listening",
            awaitState { client.state.value == UdpDiscoveryState.Listening }
        )
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
    fun `stop clears discovered peers`() {
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
            servicePort = 0,  // Auto-select
            deviceName = "TestDevice"
        )
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }

    @Test
    fun `start and stop lifecycle`() {
        val server = UdpDiscoveryServer(
            servicePort = 0,
            deviceName = "TestDevice"
        )
        server.start()
        // start() flips to Broadcasting on a background coroutine (or Error if
        // the host JVM cannot bind the broadcast socket).
        assertTrue(
            "state should reach Broadcasting or Error",
            awaitState {
                val s = server.state.value
                s == UdpDiscoveryState.Broadcasting || s is UdpDiscoveryState.Error
            }
        )
        // The server's stop() resets to Idle (NOT Stopped) — pin actual behavior.
        server.stop()
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }

    @Test
    fun `stop can be called when not started`() {
        val server = UdpDiscoveryServer(
            servicePort = 0,
            deviceName = "TestDevice"
        )
        server.stop()  // Should not throw
        // Server stop() resets to Idle (NOT Stopped).
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }
}

class UdpDiscoveryModelsTest {

    @Test
    fun `DiscoveredPeer can be created with required fields`() {
        val peer = DiscoveredPeer(
            name = "TestDevice",
            host = "127.0.0.1",
            port = 12345
        )
        assertEquals("TestDevice", peer.name)
        assertEquals(12345, peer.port)
    }

    @Test
    fun `UdpDiscoveryState has expected states`() {
        // sealed class (not enum) — reference each state to confirm it exists.
        val states: List<UdpDiscoveryState> = listOf(
            UdpDiscoveryState.Idle,
            UdpDiscoveryState.Broadcasting,
            UdpDiscoveryState.Listening,
            UdpDiscoveryState.Stopped,
            UdpDiscoveryState.Error("test")
        )
        assertEquals(5, states.size)
        assertTrue(states.any { it is UdpDiscoveryState.Error })
    }

    @Test
    fun `DiscoveredPeer equality works correctly`() {
        val host = "127.0.0.1"
        val peer1 = DiscoveredPeer("Device1", host, 12345)
        val peer2 = DiscoveredPeer("Device1", host, 12345)
        assertEquals(peer1, peer2)
    }

    @Test
    fun `UdpDiscoveryState Error can be created with message`() {
        val errorState = UdpDiscoveryState.Error("Network unavailable")
        assertTrue(errorState is UdpDiscoveryState.Error)
        assertEquals("Network unavailable", errorState.message)
    }
}
