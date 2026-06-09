package com.ymr.lancomm.data.socket

import com.ymr.lancomm.data.auth.NoOpAuthProvider
import com.ymr.lancomm.domain.model.PeerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the CURRENT observable disconnect / peer-removal behavior of
 * [TcpSocketServer] (per plan M2 / T8b). This is intentionally NOT an
 * idealized contract.
 *
 * Current behavior under test:
 *  - A peer is added to [TcpSocketServer.connectedPeers] when a client connects,
 *    keyed for storage/cleanup by `clientId = "${remoteSocketAddress}"` (e.g.
 *    "/127.0.0.1:54321") but stored in the peer list with `host =
 *    inetAddress.hostAddress` (bare ip, e.g. "127.0.0.1").
 *  - `disconnectClient` removes the peer from the list only when
 *    `"${peer.host}:${peer.port}" == clientId`. Because the two key shapes
 *    diverge (bare-ip vs `/ip:port`), that filter is a **no-op** today: a peer
 *    is NOT removed from `connectedPeers` on client disconnect.
 *  - The only path that actually clears `connectedPeers` is `stop()`.
 *
 * These assertions lock that behavior in place so the deliberately-deferred
 * `:123`-vs-`:237` key-mismatch fix (ADR follow-up #3) is a visible, intentional
 * change rather than an accidental regression.
 *
 * Uses [runBlocking] (real time) rather than `runTest` (virtual time) because it
 * drives real loopback sockets and waits on real background cleanup.
 */
class TcpSocketDisconnectBehaviorTest {

    private suspend fun <T> awaitOrNull(timeoutMs: Long = 5_000, predicate: () -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            var v = predicate()
            while (v == null) {
                delay(20)
                v = predicate()
            }
            v
        }

    @Test
    fun `client disconnect does NOT remove peer from connectedPeers (current no-op behavior)`() = runBlocking {
        // Real loopback so we exercise the actual accept/auth/cleanup path.
        val server = TcpSocketServer(authProvider = NoOpAuthProvider())
        val port = server.start()
        assertTrue("server should bind a port", port > 0)

        val client = TcpSocketClient()
        try {
            client.connect(PeerInfo("Client", "127.0.0.1", port))
            val auth = client.authenticate("Client", ByteArray(0))
            assertTrue("NoOpAuthProvider should accept", auth.success)

            // Peer should appear in connectedPeers after the handshake.
            val appeared = awaitOrNull { server.connectedPeers.value.takeIf { it.isNotEmpty() } }
            assertNotNull("peer should be tracked after connect", appeared)
            assertEquals(1, server.connectedPeers.value.size)
            val peer = server.connectedPeers.value.first()
            // Stored host is the bare ip (NOT the `/ip:port` clientId).
            assertEquals("127.0.0.1", peer.host)

            // Disconnect the client -> server's handleClient finally runs disconnectClient.
            client.disconnect()

            // CURRENT behavior: the removal filter never matches, so the peer
            // is NOT removed. Give the server time to run cleanup, then assert
            // the peer is still present.
            delay(800)
            assertEquals(
                "Current behavior: disconnectClient filter is a no-op, peer remains",
                1,
                server.connectedPeers.value.size
            )

            // The ONLY path that clears the list today is stop().
            server.stop()
            assertTrue(
                "stop() is the path that actually clears connectedPeers",
                server.connectedPeers.value.isEmpty()
            )
        } finally {
            client.disconnect()
            server.stop()
        }
    }
}
