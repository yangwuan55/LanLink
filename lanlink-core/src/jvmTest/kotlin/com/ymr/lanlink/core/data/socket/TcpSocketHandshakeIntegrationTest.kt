package com.ymr.lanlink.core.data.socket

import com.ymr.lanlink.core.crypto.TokenProofs
import com.ymr.lanlink.core.data.auth.InMemoryAuthProvider
import com.ymr.lanlink.core.data.auth.NoOpAuthProvider
import com.ymr.lanlink.core.data.pairing.InMemoryPairingRegistry
import com.ymr.lanlink.core.data.proto.AuthProtocol
import com.ymr.lanlink.core.data.proto.AuthRequest
import com.ymr.lanlink.core.data.proto.AuthResponse
import com.ymr.lanlink.core.data.proto.TokenChallenge
import com.ymr.lanlink.core.data.proto.TokenHello
import com.ymr.lanlink.core.data.proto.TokenProof
import com.ymr.lanlink.core.data.proto.decodeAuthRequest
import com.ymr.lanlink.core.data.proto.decodeAuthResponse
import com.ymr.lanlink.core.data.proto.decodeIssuedPairing
import com.ymr.lanlink.core.data.proto.decodeTokenChallenge
import com.ymr.lanlink.core.data.proto.encode
import com.ymr.lanlink.core.data.proto.readDelimited
import com.ymr.lanlink.core.data.proto.writeDelimited
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.domain.pairing.PairingRecord
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-socket integration coverage for the credential direct-connect handshake
 * (review MAJOR-1). Unlike DirectConnectServiceTest (which runs FakeLanClient),
 * these exercise the production [TcpSocketServer.handleTokenHandshake] /
 * [TcpSocketClient.authenticateWithCredential] over an actual loopback socket.
 */
class TcpSocketHandshakeIntegrationTest {

    private val pin = "123456"

    private fun rawClientSocket(port: Int): Socket {
        val selector = SelectorManager(Dispatchers.IO)
        return runBlocking {
            aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", port))
        }
    }

    // (a) PIN handshake with a registry mints a credential the client receives.
    @Test
    fun pin_handshake_with_registry_issues_decodable_credential() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val server = TcpSocketServer(
            authProvider = InMemoryAuthProvider(pin),
            pairingRegistry = registry,
            serverDeviceId = "dev-1",
            serverName = "Host-A",
        )
        val port = server.start()
        val client = TcpSocketClient()
        try {
            client.connect(PeerInfo("Host-A", "127.0.0.1", port))
            val result = client.authenticate("client-A", pin.encodeToByteArray())
            assertTrue(result.success, "PIN auth should succeed")
            val issuedBytes = result.customData
            assertNotNull(issuedBytes, "registry-enabled server must ship customData")
            assertTrue(issuedBytes.isNotEmpty(), "customData must be non-empty")
            val issued = decodeIssuedPairing(issuedBytes)
            assertTrue(issued.pairingId.isNotEmpty(), "issued pairingId present")
            assertEquals(AuthProtocol.SECRET_BYTES, issued.secret.size, "issued secret is 32 bytes")
            assertEquals("dev-1", issued.serverDeviceId)
            assertEquals("Host-A", issued.serverName)
            // Server persisted the record under the same pairingId/secret.
            val record = registry.find(issued.pairingId)
            assertNotNull(record, "server registry stored the record")
            assertContentEquals(issued.secret, record.secret, "stored secret matches issued")
        } finally {
            client.disconnect()
            server.stop()
        }
    }

    // (a2) Pairing window control: a PIN handshake is rejected while the window is
    // closed (default), accepted after setPairingPin(pin), and rejected again after
    // setPairingPin(null).
    @Test
    fun pin_handshake_gated_by_pairing_window() = runBlocking {
        val registry = InMemoryPairingRegistry()
        // Server starts with the window CLOSED (authProvider null = default).
        val server = TcpSocketServer(
            pairingRegistry = registry,
            serverDeviceId = "dev-1",
            serverName = "Host-A",
        )
        val port = server.start()
        try {
            // Window closed -> PIN rejected with PAIRING_CLOSED.
            TcpSocketClient().let { c ->
                c.connect(PeerInfo("Host-A", "127.0.0.1", port))
                val r = c.authenticate("client-A", pin.encodeToByteArray())
                assertFalse(r.success, "closed window must reject PIN")
                assertEquals(AuthProtocol.REASON_PAIRING_CLOSED, r.message)
                c.disconnect()
            }

            // Open the window -> PIN succeeds and a credential is minted.
            server.setPairingPin(pin)
            TcpSocketClient().let { c ->
                c.connect(PeerInfo("Host-A", "127.0.0.1", port))
                val r = c.authenticate("client-A", pin.encodeToByteArray())
                assertTrue(r.success, "open window must accept correct PIN")
                assertNotNull(r.customData)
                c.disconnect()
            }

            // Close again -> PIN rejected once more.
            server.setPairingPin(null)
            TcpSocketClient().let { c ->
                c.connect(PeerInfo("Host-A", "127.0.0.1", port))
                val r = c.authenticate("client-A", pin.encodeToByteArray())
                assertFalse(r.success, "reclosed window must reject PIN")
                assertEquals(AuthProtocol.REASON_PAIRING_CLOSED, r.message)
                c.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    // (a3) Token reconnect (authScheme=1) works even while the PIN window is closed.
    @Test
    fun token_handshake_succeeds_while_pairing_window_closed() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val pairingId = "pair-closed"
        val secret = ByteArray(AuthProtocol.SECRET_BYTES) { (it + 2).toByte() }
        registry.save(PairingRecord(pairingId, secret, "client-A", 0, 0))
        // Window closed throughout.
        val server = TcpSocketServer(pairingRegistry = registry)
        val port = server.start()
        val client = TcpSocketClient()
        try {
            client.connect(PeerInfo("Host-A", "127.0.0.1", port))
            val result = client.authenticateWithCredential("client-A", pairingId, secret)
            assertTrue(result.success, "token reconnect must work with PIN window closed, was: ${result.message}")
            assertEquals(AuthProtocol.MESSAGE_OK, result.message)
        } finally {
            client.disconnect()
            server.stop()
        }
    }

    // (b) A genuine token handshake against a pre-seeded record succeeds.
    @Test
    fun token_handshake_succeeds_for_known_pairing() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val pairingId = "pair-known"
        val secret = ByteArray(AuthProtocol.SECRET_BYTES) { it.toByte() }
        registry.save(
            PairingRecord(
                pairingId = pairingId,
                secret = secret,
                clientDeviceName = "client-A",
                pairedAt = 0,
                lastSeenAt = 0,
            )
        )
        val server = TcpSocketServer(authProvider = NoOpAuthProvider(), pairingRegistry = registry)
        val port = server.start()
        val client = TcpSocketClient()
        try {
            client.connect(PeerInfo("Host-A", "127.0.0.1", port))
            val result = client.authenticateWithCredential("client-A", pairingId, secret)
            assertTrue(result.success, "token handshake should succeed, was: ${result.message}")
            assertEquals(AuthProtocol.MESSAGE_OK, result.message)
        } finally {
            client.disconnect()
            server.stop()
        }
    }

    // (c) Unknown pairingId is rejected with PAIRING_REVOKED.
    @Test
    fun token_handshake_unknown_pairing_is_revoked() = runBlocking {
        val server = TcpSocketServer(
            authProvider = NoOpAuthProvider(),
            pairingRegistry = InMemoryPairingRegistry(),
        )
        val port = server.start()
        val client = TcpSocketClient()
        try {
            client.connect(PeerInfo("Host-A", "127.0.0.1", port))
            val result = client.authenticateWithCredential(
                "client-A",
                "no-such-pairing",
                ByteArray(AuthProtocol.SECRET_BYTES) { 7 },
            )
            assertFalse(result.success, "unknown pairingId must be rejected")
            assertEquals(AuthProtocol.REASON_PAIRING_REVOKED, result.message)
        } finally {
            client.disconnect()
            server.stop()
        }
    }

    // (d) A wrong clientProof is rejected with INVALID_PROOF. Driven with raw
    // frames so we send a deliberately corrupt proof past the real client logic.
    @Test
    fun token_handshake_wrong_client_proof_is_invalid() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val pairingId = "pair-d"
        val secret = ByteArray(AuthProtocol.SECRET_BYTES) { (it + 1).toByte() }
        registry.save(
            PairingRecord(pairingId, secret, "client-A", 0, 0)
        )
        val server = TcpSocketServer(authProvider = NoOpAuthProvider(), pairingRegistry = registry)
        val port = server.start()
        val socket = rawClientSocket(port)
        try {
            val read = socket.openReadChannel()
            val write = socket.openWriteChannel()

            val clientNonce = ByteArray(AuthProtocol.NONCE_BYTES) { 9 }
            val hello = TokenHello(pairingId = pairingId, clientNonce = clientNonce)
            write.writeDelimited(
                AuthRequest(
                    deviceName = "client-A",
                    authScheme = AuthProtocol.AUTH_SCHEME_TOKEN,
                    customData = hello.encode(),
                ).encode()
            )

            val challengeResp = decodeAuthResponse(read.readDelimited())
            assertTrue(challengeResp.success)
            assertEquals(AuthProtocol.MESSAGE_CHALLENGE, challengeResp.message)

            // Send a deliberately wrong client proof.
            write.writeDelimited(TokenProof(clientProof = ByteArray(32) { 0 }).encode())

            val finalResp = decodeAuthResponse(read.readDelimited())
            assertFalse(finalResp.success)
            assertEquals(AuthProtocol.REASON_INVALID_PROOF, finalResp.message)
        } finally {
            socket.close()
            server.stop()
        }
    }

    // (e) When the server proof does not verify, the real client returns a
    // mismatch verdict and MUST NOT write a third frame. We act as a raw server
    // that emits a bogus serverProof, then assert no client proof ever arrives.
    @Test
    fun client_aborts_without_third_frame_on_server_proof_mismatch() = runBlocking {
        val secret = ByteArray(AuthProtocol.SECRET_BYTES) { (it + 3).toByte() }
        val selector = SelectorManager(Dispatchers.IO)
        val serverSocket = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        val port = (serverSocket.localAddress as InetSocketAddress).port

        val client = TcpSocketClient()
        var thirdFrameSeen = false
        var clientResult: com.ymr.lanlink.core.net.AuthHandshakeResult? = null
        try {
            coroutineScope {
                // Raw server coroutine: read hello, reply with a corrupt
                // serverProof, then watch for any (illegitimate) third frame.
                launch(Dispatchers.IO) {
                    val accepted = serverSocket.accept()
                    try {
                        val sRead = accepted.openReadChannel()
                        val sWrite = accepted.openWriteChannel()
                        decodeAuthRequest(sRead.readDelimited())
                        val serverNonce = ByteArray(AuthProtocol.NONCE_BYTES) { 5 }
                        // Intentionally garbage server proof — does not verify.
                        val badChallenge = TokenChallenge(
                            serverNonce = serverNonce,
                            serverProof = ByteArray(32) { 0 },
                        )
                        sWrite.writeDelimited(
                            AuthResponse(
                                success = true,
                                message = AuthProtocol.MESSAGE_CHALLENGE,
                                customData = badChallenge.encode(),
                            ).encode()
                        )
                        // If the client (incorrectly) sent a third frame, read it.
                        val maybeThird = withTimeoutOrNull(1_500) { sRead.readDelimited() }
                        if (maybeThird != null) thirdFrameSeen = true
                    } finally {
                        accepted.close()
                    }
                }

                client.connect(PeerInfo("Host-A", "127.0.0.1", port))
                clientResult = client.authenticateWithCredential("client-A", "pair-e", secret)
            }
        } finally {
            client.disconnect()
            serverSocket.close()
            selector.close()
        }

        val r = clientResult
        assertNotNull(r)
        assertFalse(r.success, "client must reject a spoofed server")
        assertEquals(AuthProtocol.REASON_SERVER_PROOF_MISMATCH, r.message)
        assertFalse(thirdFrameSeen, "client must NOT send a client proof to a spoofed server")
    }

    // (f) MINOR-3: a clientProof captured from one session is replayed verbatim
    // in a new connection (new serverNonce) and rejected with INVALID_PROOF.
    @Test
    fun replayed_client_proof_is_rejected_in_new_session() = runBlocking {
        val registry = InMemoryPairingRegistry()
        val pairingId = "pair-f"
        val secret = ByteArray(AuthProtocol.SECRET_BYTES) { (it + 11).toByte() }
        registry.save(PairingRecord(pairingId, secret, "client-A", 0, 0))
        val server = TcpSocketServer(authProvider = NoOpAuthProvider(), pairingRegistry = registry)
        val port = server.start()

        // Session 1: drive raw frames, capture the legitimate clientProof.
        val capturedProof = runHandshakeCapturingProof(port, pairingId, secret)
        assertNotNull(capturedProof)

        // Session 2: new connection (fresh serverNonce), replay the old proof.
        val socket = rawClientSocket(port)
        try {
            val read = socket.openReadChannel()
            val write = socket.openWriteChannel()
            val clientNonce = ByteArray(AuthProtocol.NONCE_BYTES) { 2 }
            write.writeDelimited(
                AuthRequest(
                    deviceName = "client-A",
                    authScheme = AuthProtocol.AUTH_SCHEME_TOKEN,
                    customData = TokenHello(pairingId, clientNonce).encode(),
                ).encode()
            )
            val challengeResp = decodeAuthResponse(read.readDelimited())
            assertTrue(challengeResp.success)
            // Replay the proof from session 1 against this session's nonce.
            write.writeDelimited(TokenProof(clientProof = capturedProof).encode())
            val finalResp = decodeAuthResponse(read.readDelimited())
            assertFalse(finalResp.success, "replayed proof must be rejected")
            assertEquals(AuthProtocol.REASON_INVALID_PROOF, finalResp.message)
        } finally {
            socket.close()
            server.stop()
        }
    }

    private suspend fun runHandshakeCapturingProof(
        port: Int,
        pairingId: String,
        secret: ByteArray,
    ): ByteArray {
        val socket = rawClientSocket(port)
        try {
            val read = socket.openReadChannel()
            val write = socket.openWriteChannel()
            val clientNonce = ByteArray(AuthProtocol.NONCE_BYTES) { 1 }
            write.writeDelimited(
                AuthRequest(
                    deviceName = "client-A",
                    authScheme = AuthProtocol.AUTH_SCHEME_TOKEN,
                    customData = TokenHello(pairingId, clientNonce).encode(),
                ).encode()
            )
            val challengeResp = decodeAuthResponse(read.readDelimited())
            val challenge = decodeTokenChallenge(challengeResp.customData)
            val proof = TokenProofs.clientProof(secret, challenge.serverNonce, clientNonce)
            write.writeDelimited(TokenProof(clientProof = proof).encode())
            val finalResp = decodeAuthResponse(read.readDelimited())
            assertTrue(finalResp.success, "session 1 handshake should succeed")
            return proof
        } finally {
            socket.close()
        }
    }
}
