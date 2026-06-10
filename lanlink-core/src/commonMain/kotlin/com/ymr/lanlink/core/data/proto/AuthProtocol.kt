package com.ymr.lanlink.core.data.proto

/**
 * Single source of truth for the auth-handshake protocol constants shared by
 * [com.ymr.lanlink.core.data.socket.TcpSocketServer] and
 * [com.ymr.lanlink.core.data.socket.TcpSocketClient]. Keeping them here prevents
 * the two ends from drifting (see review NIT-4 / MINOR-4).
 */
object AuthProtocol {
    /** authScheme value selecting the legacy PIN handshake (proto3 default). */
    const val AUTH_SCHEME_PIN = 0

    /** authScheme value selecting the HMAC credential direct-connect handshake. */
    const val AUTH_SCHEME_TOKEN = 1

    /** Credential blob scheme tag persisted in [PairingCredential.scheme]. */
    const val SCHEME_HMAC_SHA256_V1 = "hmac-sha256-v1"

    /**
     * [AuthResponse.message] marking the intermediate token-challenge frame
     * (success=true, customData carries a [TokenChallenge]). The client MUST see
     * this value AND a non-empty customData before decoding the challenge.
     */
    const val MESSAGE_CHALLENGE = "CHALLENGE"

    /** [AuthResponse.message] for a successful (terminal) handshake. */
    const val MESSAGE_OK = "OK"

    /** Rejection reason: unknown/revoked pairingId, or issuance not enabled. */
    const val REASON_PAIRING_REVOKED = "PAIRING_REVOKED"

    /** Rejection reason: a PIN handshake arrived while the pairing window is closed. */
    const val REASON_PAIRING_CLOSED = "PAIRING_CLOSED"

    /** Rejection reason: client proof did not verify. */
    const val REASON_INVALID_PROOF = "INVALID_PROOF"

    /** Client-side verdict: the server proof did not verify (possible spoof). */
    const val REASON_SERVER_PROOF_MISMATCH = "SERVER_PROOF_MISMATCH"

    /** Nonce length in bytes for both client and server nonces. */
    const val NONCE_BYTES = 16

    /** Per-pairing secret length in bytes minted during PIN issuance. */
    const val SECRET_BYTES = 32
}
