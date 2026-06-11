package com.ymr.lanlink.core.data.proto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * kotlinx-serialization-protobuf models mirroring lan_service.proto (proto3).
 *
 * Field numbers are pinned via [ProtoNumber] to stay wire-compatible with the
 * standard protobuf wire format (proto3). Type mapping:
 * string->String, int64->Long, int32->Int, bytes->ByteArray, bool->Boolean.
 *
 * All fields carry proto3 default values ("" / 0 / 0L / false / empty bytes).
 *
 * NOTE: data classes containing [ByteArray] fall back to reference equality in
 * generated equals/hashCode. Do not rely on structural equality of these types
 * in production code; compare byte payloads by content where it matters.
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LanMessage(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val timestamp: Long = 0L,
    @ProtoNumber(3) val payload: ByteArray = ByteArray(0),
    @ProtoNumber(4) val type: Int = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthRequest(
    @ProtoNumber(1) val deviceName: String = "",
    @ProtoNumber(2) val credentials: ByteArray = ByteArray(0),
    @ProtoNumber(3) val customData: ByteArray = ByteArray(0),
    // 0 = PIN (legacy path), 1 = TokenHello (credential direct-connect). Appended
    // field number; proto3 readers that predate it default it to 0 (PIN), so
    // existing peers stay wire-compatible.
    @ProtoNumber(4) val authScheme: Int = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthResponse(
    @ProtoNumber(1) val success: Boolean = false,
    @ProtoNumber(2) val message: String = "",
    @ProtoNumber(3) val customData: ByteArray = ByteArray(0),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConnectionStatus(
    @ProtoNumber(1) val connected: Boolean = false,
    @ProtoNumber(2) val peerName: String = "",
)

// region pairing-credential direct-connect (HMAC-SHA256 challenge-response)

/**
 * Carried in [AuthResponse.customData] after a successful PIN handshake: the
 * server-minted pairing record the client persists inside its localData blob.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IssuedPairing(
    @ProtoNumber(1) val pairingId: String = "",
    @ProtoNumber(2) val secret: ByteArray = ByteArray(0),
    @ProtoNumber(3) val serverDeviceId: String = "",
    @ProtoNumber(4) val serverName: String = "",
)

/**
 * The opaque client-side credential blob (protobuf, then Base64) the host App
 * stores as `localData`. `secret` never travels the network after issuance.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PairingCredential(
    @ProtoNumber(1) val version: Int = 1,
    @ProtoNumber(2) val scheme: String = "hmac-sha256-v1",
    @ProtoNumber(3) val pairingId: String = "",
    @ProtoNumber(4) val secret: ByteArray = ByteArray(0),
    @ProtoNumber(5) val serverDeviceId: String = "",
    @ProtoNumber(6) val serverName: String = "",
    @ProtoNumber(7) val lastHost: String = "",
    @ProtoNumber(8) val lastPort: Int = 0,
    @ProtoNumber(9) val pairedAt: Long = 0L,
)

/** Step 1 (C->S): carried in [AuthRequest.customData] when authScheme=1. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TokenHello(
    @ProtoNumber(1) val pairingId: String = "",
    @ProtoNumber(2) val clientNonce: ByteArray = ByteArray(0),
)

/** Step 2 (S->C): carried in [AuthResponse.customData] on a known pairingId. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TokenChallenge(
    @ProtoNumber(1) val serverNonce: ByteArray = ByteArray(0),
    @ProtoNumber(2) val serverProof: ByteArray = ByteArray(0),
)

/** Step 3 (C->S): client's HMAC proof, sent only after serverProof verifies. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TokenProof(
    @ProtoNumber(1) val clientProof: ByteArray = ByteArray(0),
)

// endregion

// region server-side pairing persistence

/**
 * On-disk encoding of a server-side
 * [com.ymr.lanlink.core.domain.pairing.PairingRecord]. Used only by the
 * persistent registry (e.g.
 * [com.ymr.lanlink.core.data.pairing.DataStorePairingRegistry]); never travels
 * the network. `internal` — not part of the public wire protocol.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class StoredPairingRecord(
    @ProtoNumber(1) val pairingId: String = "",
    @ProtoNumber(2) val secret: ByteArray = ByteArray(0),
    @ProtoNumber(3) val clientDeviceName: String = "",
    @ProtoNumber(4) val pairedAt: Long = 0L,
    @ProtoNumber(5) val lastSeenAt: Long = 0L,
)

// endregion
