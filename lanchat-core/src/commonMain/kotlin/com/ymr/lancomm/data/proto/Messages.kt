package com.ymr.lancomm.data.proto

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
