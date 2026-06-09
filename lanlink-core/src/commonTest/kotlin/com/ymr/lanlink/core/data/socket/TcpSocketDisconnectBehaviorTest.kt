package com.ymr.lanlink.core.data.socket

import com.ymr.lanlink.core.data.auth.NoOpAuthProvider
import com.ymr.lanlink.core.domain.model.PeerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the CURRENT observable disconnect / peer-removal behavior of
 * [TcpSocketServer] (per plan M2 / T8b). This is intentionally NOT an
 * idealized contract.
 *
 * Current behavior under test:
 *  - A peer is added to [TcpSocketServer.connectedPeers] when a client connects.
 *  - After disconnect, the peer IS removed from connectedPeers because the Ktor
 *    implementation uses a stable clientId and proper cleanup in disconnectClient.
 *  - The only path that clears remaining state is stop().
 *
 * Uses [runBlocking] (real time) rather than runTest (virtual time) because it
 * drives real loopback sockets and waits on real background cleanup.
 */
class TcpSocketDisconnectBehaviorTest {

    // Helper: poll until list is non-empty or timeout
    private suspend fun awaitNonEmpty(timeoutMs: Long = 5_000, get: () -> List<PeerInfo>): List<PeerInfo>? =
        withTimeoutOrNull(timeoutMs) {
            var v = get()
            while (v.isEmpty()) {
                delay(20)
                v = get()
            }
            v
        }

    // Helper: poll until list is empty or timeout
    private suspend fun awaitEmpty(timeoutMs: Long = 3_000, get: () -> List<PeerInfo>): List<PeerInfo>? =
        withTimeoutOrNull(timeoutMs) {
            var v = get()
            while (v.isNotEmpty()) {
                delay(20)
                v = get()
            }
            v
        }

    @Test
    fun client_disconnect_removes_peer_from_connectedPeers() = runBlocking {
        // Real loopback so we exercise the actual accept/auth/cleanup path.
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        val port = server.start()
        assertTrue(port > 0, "server should bind a port")

        val client = TcpSocketClient()
        try {
            client.connect(PeerInfo("Client", "127.0.0.1", port))
            val auth = client.authenticate("Client", ByteArray(0))
            assertTrue(auth.success, "NoOpAuthProvider should accept")

            // Peer should appear in connectedPeers after the handshake.
            val appeared = awaitNonEmpty { server.connectedPeers.value }
            assertNotNull(appeared, "peer should be tracked after connect")
            assertEquals(1, server.connectedPeers.value.size)
            val peer = server.connectedPeers.value.first()
            // Ktor's InetSocketAddress.hostname resolves "127.0.0.1" to "localhost" on some JVMs.
            assertTrue(
                peer.host == "127.0.0.1" || peer.host == "localhost",
                "Stored host should be loopback address, was: ${peer.host}"
            )

            // Disconnect the client -> server's handleClient finally runs disconnectClient.
            client.disconnect()

            // Ktor implementation: disconnectClient properly cleans up via clientPeers map.
            // Give the server time to run cleanup.
            val cleared = awaitEmpty { server.connectedPeers.value }
            assertNotNull(
                cleared,
                "Ktor disconnectClient cleans up connectedPeers after client disconnect"
            )

            // stop() should also leave the list empty.
            server.stop()
            assertTrue(server.connectedPeers.value.isEmpty(), "stop() leaves connectedPeers empty")
        } finally {
            client.disconnect()
            server.stop()
        }
    }
}
