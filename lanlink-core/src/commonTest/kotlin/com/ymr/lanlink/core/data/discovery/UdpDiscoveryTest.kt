package com.ymr.lanlink.core.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Waits (real time) for [predicate] to hold, since start() transitions state on a background coroutine. */
private fun awaitState(timeoutMs: Long = 3_000, predicate: () -> Boolean): Boolean = runBlocking {
    withTimeoutOrNull(timeoutMs) {
        while (!predicate()) delay(20)
        true
    } ?: false
}

class UdpDiscoveryClientTest {

    @Test
    fun initial_state_is_Idle() {
        // The implementation initializes _state to Idle (it only becomes
        // Stopped after stop()); pin that actual behavior.
        val client = UdpDiscoveryClient()
        assertEquals(UdpDiscoveryState.Idle, client.state.value)
    }

    @Test
    fun discoveredPeers_is_empty_initially() {
        val client = UdpDiscoveryClient()
        assertTrue(client.discoveredPeers.value.isEmpty())
    }

    @Test
    fun start_transitions_state_to_Listening() {
        val client = UdpDiscoveryClient()
        client.start()
        // start() binds the socket + flips state on a background coroutine.
        assertTrue(
            awaitState { client.state.value == UdpDiscoveryState.Listening },
            "state should reach Listening"
        )
        client.stop()
    }

    @Test
    fun stop_transitions_state_back_to_Stopped() {
        val client = UdpDiscoveryClient()
        client.start()
        client.stop()
        assertEquals(UdpDiscoveryState.Stopped, client.state.value)
    }

    @Test
    fun start_can_be_called_multiple_times_safely() {
        val client = UdpDiscoveryClient()
        client.start()
        client.start()  // Should not throw
        assertTrue(
            awaitState { client.state.value == UdpDiscoveryState.Listening },
            "state should reach Listening"
        )
        client.stop()
    }

    @Test
    fun stop_can_be_called_multiple_times_safely() {
        val client = UdpDiscoveryClient()
        client.stop()
        client.stop()
        // Should not throw
        assertEquals(UdpDiscoveryState.Stopped, client.state.value)
    }

    @Test
    fun stop_clears_discovered_peers() {
        val client = UdpDiscoveryClient()
        client.start()
        // Stop should clear peers
        client.stop()
        assertTrue(client.discoveredPeers.value.isEmpty())
    }
}

class UdpDiscoveryServerTest {

    @Test
    fun initial_state_is_Idle() {
        val server = UdpDiscoveryServer(
            servicePort = 0,  // Auto-select
            deviceName = "TestDevice"
        )
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }

    @Test
    fun start_and_stop_lifecycle() {
        val server = UdpDiscoveryServer(
            servicePort = 0,
            deviceName = "TestDevice"
        )
        server.start()
        // start() flips to Broadcasting on a background coroutine (or Error if
        // the host JVM cannot bind the broadcast socket).
        assertTrue(
            awaitState {
                val s = server.state.value
                s == UdpDiscoveryState.Broadcasting || s is UdpDiscoveryState.Error
            },
            "state should reach Broadcasting or Error"
        )
        // The server's stop() resets to Idle (NOT Stopped) — pin actual behavior.
        server.stop()
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }

    @Test
    fun stop_can_be_called_when_not_started() {
        val server = UdpDiscoveryServer(
            servicePort = 0,
            deviceName = "TestDevice"
        )
        server.stop()  // Should not throw
        // Server stop() resets to Idle (NOT Stopped).
        assertEquals(UdpDiscoveryState.Idle, server.state.value)
    }
}

class ParseDiscoveryMessageTest {

    @Test
    fun parses_five_field_message_with_serverDeviceId() {
        val peer = parseDiscoveryMessage("LANCHAT_DISCOVER|Pixel|10.0.0.5|4242|abc-123")
        assertNotNull(peer)
        assertEquals("Pixel", peer!!.name)
        assertEquals("10.0.0.5", peer.host)
        assertEquals(4242, peer.port)
        assertEquals("abc-123", peer.serverDeviceId)
    }

    @Test
    fun parses_legacy_four_field_message_with_empty_serverDeviceId() {
        // Backward compat: an advertiser that predates the 5th field still parses,
        // yielding an empty serverDeviceId rather than failing.
        val peer = parseDiscoveryMessage("LANCHAT_DISCOVER|Pixel|10.0.0.5|4242")
        assertNotNull(peer)
        assertEquals("", peer!!.serverDeviceId)
        assertEquals(4242, peer.port)
    }

    @Test
    fun returns_null_for_non_discovery_or_bad_port() {
        assertEquals(null, parseDiscoveryMessage("SOMETHING_ELSE|a|b|c"))
        assertEquals(null, parseDiscoveryMessage("LANCHAT_DISCOVER|Pixel|10.0.0.5|notaport"))
        assertEquals(null, parseDiscoveryMessage("LANCHAT_DISCOVER|Pixel|10.0.0.5"))
    }
}

class PeerTableTest {

    @Test
    fun same_serverDeviceId_on_new_port_replaces_old_entry() {
        val table = PeerTable(ttlMs = 6000)
        table.upsert(DiscoveredPeer("Srv", "10.0.0.5", 4242, "id-1"), now = 1000)
        table.upsert(DiscoveredPeer("Srv", "10.0.0.5", 9999, "id-1"), now = 2000)

        val snap = table.snapshot()
        assertEquals(1, snap.size)
        assertEquals(9999, snap.single().port) // new address replaced the stale one
    }

    @Test
    fun distinct_serverDeviceIds_coexist() {
        val table = PeerTable(ttlMs = 6000)
        table.upsert(DiscoveredPeer("A", "10.0.0.1", 4000, "id-a"), now = 1000)
        table.upsert(DiscoveredPeer("B", "10.0.0.2", 5000, "id-b"), now = 1000)
        assertEquals(2, table.snapshot().size)
    }

    @Test
    fun entries_without_id_dedup_by_host_port() {
        val table = PeerTable(ttlMs = 6000)
        table.upsert(DiscoveredPeer("L", "10.0.0.9", 7000), now = 1000)
        table.upsert(DiscoveredPeer("L", "10.0.0.9", 7000), now = 2000)
        assertEquals(1, table.snapshot().size)
    }

    @Test
    fun expired_entry_is_evicted() {
        val table = PeerTable(ttlMs = 6000)
        table.upsert(DiscoveredPeer("Srv", "10.0.0.5", 4242, "id-1"), now = 1000)
        // A later upsert of a different peer past the TTL evicts the stale one.
        table.upsert(DiscoveredPeer("Other", "10.0.0.6", 5000, "id-2"), now = 8000)

        val snap = table.snapshot()
        assertEquals(1, snap.size)
        assertEquals("id-2", snap.single().serverDeviceId)
    }

    @Test
    fun evictExpired_reports_removal() {
        val table = PeerTable(ttlMs = 6000)
        table.upsert(DiscoveredPeer("Srv", "10.0.0.5", 4242, "id-1"), now = 1000)
        assertFalse(table.evictExpired(now = 5000)) // still fresh
        assertTrue(table.evictExpired(now = 8000))  // now past TTL
        assertTrue(table.snapshot().isEmpty())
    }
}

class UdpDiscoveryModelsTest {

    @Test
    fun DiscoveredPeer_can_be_created_with_required_fields() {
        val peer = DiscoveredPeer(
            name = "TestDevice",
            host = "127.0.0.1",
            port = 12345
        )
        assertEquals("TestDevice", peer.name)
        assertEquals(12345, peer.port)
    }

    @Test
    fun UdpDiscoveryState_has_expected_states() {
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
    fun DiscoveredPeer_equality_works_correctly() {
        val host = "127.0.0.1"
        val peer1 = DiscoveredPeer("Device1", host, 12345)
        val peer2 = DiscoveredPeer("Device1", host, 12345)
        assertEquals(peer1, peer2)
    }

    @Test
    fun UdpDiscoveryState_Error_can_be_created_with_message() {
        val errorState: UdpDiscoveryState = UdpDiscoveryState.Error("Network unavailable")
        assertTrue(errorState is UdpDiscoveryState.Error)
        assertEquals("Network unavailable", (errorState as UdpDiscoveryState.Error).message)
    }
}
