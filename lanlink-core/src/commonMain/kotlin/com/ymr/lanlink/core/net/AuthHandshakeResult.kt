package com.ymr.lanlink.core.net

/**
 * Outcome of the PIN auth handshake, expressed in domain terms so the wire
 * protocol (protobuf) never leaks into [commonMain]. Platform [LanClient]
 * implementations perform the actual exchange and map it to this result.
 */
data class AuthHandshakeResult(
    val success: Boolean,
    val message: String
)
