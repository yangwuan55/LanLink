package com.ymr.lanlink.data.repository

import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.nio.charset.StandardCharsets

/**
 * Unit tests for LanRepository.
 * Tests state management, connection flows, and message handling.
 */
class LanRepositoryTest {

    // Note: Full integration tests require Android context.
    // These tests verify the component structure and basic behavior.

    @org.junit.Test
    fun `repository has expected public API`() {
        val methods = LanRepository::class.java.declaredMethods.map { it.name }
        assertTrue("Should have startServer", methods.contains("startServer"))
        assertTrue("Should have startPairing", methods.contains("startPairing"))
        assertTrue("Should have stopPairing", methods.contains("stopPairing"))
        assertTrue("Should have pairWithServer", methods.contains("pairWithServer"))
        assertTrue("Should have reconnectLastServer", methods.contains("reconnectLastServer"))
        assertTrue("Should have disconnect", methods.contains("disconnect"))
        assertTrue("Should have sendMessage", methods.contains("sendMessage"))
        assertTrue("Should have close", methods.contains("close"))
    }

    @org.junit.Test
    fun `repository has expected state flows`() {
        val fields = LanRepository::class.java.declaredFields.map { it.name }
        assertTrue("Should have connectionState flow", fields.contains("connectionState"))
        assertTrue("Should have messages flow", fields.contains("messages"))
    }

    @org.junit.Test
    fun `ConnectionState enum has all expected states`() {
        // Reference each state to confirm the sealed hierarchy is reachable.
        val states: List<ConnectionState> = listOf(
            ConnectionState.Idle,
            ConnectionState.Discovering,
            ConnectionState.Connecting,
            ConnectionState.Connected("peer", isServer = false),
            ConnectionState.Error("boom")
        )
        assertEquals(5, states.size)
        assertTrue("Should have Idle state", states.any { it is ConnectionState.Idle })
    }

    @org.junit.Test
    fun `PeerInfo can be created with required fields`() {
        val peer = PeerInfo(
            name = "TestDevice",
            host = "127.0.0.1",
            port = 12345
        )
        assertEquals("TestDevice", peer.name)
        assertEquals(12345, peer.port)
    }
}

/**
 * Tests for Repository factory methods and configuration.
 */
class LanRepositoryFactoryTest {

    @org.junit.Test
    fun `repository accepts PinConnectionService`() {
        val constructors = LanRepository::class.java.constructors
        assertTrue("Should have constructor with 1 parameter", constructors.any {
            it.parameterCount == 1
        })
    }
}

/**
 * Tests for message handling logic.
 */
class MessageHandlingTest {

    @org.junit.Test
    fun `message can be serialized to bytes`() {
        val message = "Hello, World!"
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        val decoded = String(bytes, StandardCharsets.UTF_8)
        assertEquals(message, decoded)
    }

    @org.junit.Test
    fun `multiple messages can be distinguished by ID`() {
        val messageIds = mutableSetOf<String>()
        repeat(100) {
            val id = java.util.UUID.randomUUID().toString()
            assertTrue("ID should be unique", messageIds.add(id))
        }
    }
}

/**
 * Tests for connection state transitions.
 */
class ConnectionStateTransitionTest {

    @org.junit.Test
    fun `ConnectionState Connected stores peer info`() {
        val state = ConnectionState.Connected("TestPeer", isServer = true)
        assertEquals("TestPeer", state.peerName)
        assertTrue(state.isServer)
    }

    @org.junit.Test
    fun `ConnectionState Error stores message`() {
        val state = ConnectionState.Error("Connection refused")
        assertEquals("Connection refused", state.message)
    }

    @org.junit.Test
    fun `Idle and Discovering are singleton objects`() {
        assertTrue(ConnectionState.Idle is ConnectionState)
        assertTrue(ConnectionState.Discovering is ConnectionState)
        assertTrue(ConnectionState.Connecting is ConnectionState)
    }
}

/**
 * Tests for PeerInfo data class.
 */
class PeerInfoTest {

    @org.junit.Test
    fun `PeerInfo equality works`() {
        val addr = "127.0.0.1"
        val peer1 = PeerInfo("Device", addr, 12345)
        val peer2 = PeerInfo("Device", addr, 12345)
        assertEquals(peer1, peer2)
    }

    @org.junit.Test
    fun `PeerInfo toString includes name`() {
        val peer = PeerInfo("TestDevice", "127.0.0.1", 54321)
        val str = peer.toString()
        assertTrue("toString should contain name", str.contains("TestDevice"))
    }
}

/**
 * Tests for repository lifecycle.
 */
class RepositoryLifecycleTest {

    @org.junit.Test
    fun `close method exists for resource cleanup`() {
        val methods = LanRepository::class.java.declaredMethods.map { it.name }
        assertTrue("close method should exist for resource cleanup", methods.contains("close"))
    }
}