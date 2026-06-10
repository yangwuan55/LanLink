package com.ymr.lanlink.core.crypto

/**
 * Shared challenge-response proof construction for the credential direct-connect
 * handshake. Server and client compute proofs through the SAME functions so the
 * domain-separation tags ("srv"/"cli") and nonce ordering can never drift apart.
 *
 * serverProof = HMAC(secret, "srv" ‖ clientNonce ‖ serverNonce)
 * clientProof = HMAC(secret, "cli" ‖ serverNonce ‖ clientNonce)
 *
 * The "srv"/"cli" tags provide domain separation so a server proof can never be
 * replayed as a client proof (reflection attack). Fresh nonces each connection
 * defeat replay.
 */
object TokenProofs {

    private val SRV_TAG = "srv".encodeToByteArray()
    private val CLI_TAG = "cli".encodeToByteArray()

    fun serverProof(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        Hmac.sha256(secret, SRV_TAG + clientNonce + serverNonce)

    fun clientProof(secret: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        Hmac.sha256(secret, CLI_TAG + serverNonce + clientNonce)
}
