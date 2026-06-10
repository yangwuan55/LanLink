package com.ymr.lanlink.core.data.proto

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Single ProtoBuf instance. Default config keeps `encodeDefaults = false`, which
 * matches proto3 semantics (default-valued fields are not written on the wire).
 */
@OptIn(ExperimentalSerializationApi::class)
val protoBuf: ProtoBuf = ProtoBuf

// region message encode/decode

@OptIn(ExperimentalSerializationApi::class)
fun LanMessage.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeLanMessage(bytes: ByteArray): LanMessage = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun AuthRequest.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeAuthRequest(bytes: ByteArray): AuthRequest = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun AuthResponse.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeAuthResponse(bytes: ByteArray): AuthResponse = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun ConnectionStatus.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeConnectionStatus(bytes: ByteArray): ConnectionStatus = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun IssuedPairing.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeIssuedPairing(bytes: ByteArray): IssuedPairing = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun PairingCredential.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodePairingCredential(bytes: ByteArray): PairingCredential = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun TokenHello.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeTokenHello(bytes: ByteArray): TokenHello = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun TokenChallenge.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeTokenChallenge(bytes: ByteArray): TokenChallenge = protoBuf.decodeFromByteArray(bytes)

@OptIn(ExperimentalSerializationApi::class)
fun TokenProof.encode(): ByteArray = protoBuf.encodeToByteArray(this)

@OptIn(ExperimentalSerializationApi::class)
fun decodeTokenProof(bytes: ByteArray): TokenProof = protoBuf.decodeFromByteArray(bytes)

// endregion

// region varint (LEB128, base-128) — pure functions

/**
 * Encodes a non-negative [Int] as an unsigned base-128 varint, identical to the
 * length prefix used by protobuf's length-delimited framing.
 */
fun writeVarint(value: Int): ByteArray {
    require(value >= 0) { "Varint length must be non-negative, was $value" }
    val out = ArrayList<Byte>(5)
    var v = value
    while (true) {
        val b = v and 0x7F
        v = v ushr 7
        if (v == 0) {
            out.add(b.toByte())
            break
        } else {
            out.add((b or 0x80).toByte())
        }
    }
    return out.toByteArray()
}

/**
 * Reads an unsigned base-128 varint from [bytes] starting at [offset].
 * Returns the decoded value paired with the number of bytes consumed.
 */
fun readVarint(bytes: ByteArray, offset: Int = 0): Pair<Int, Int> {
    var result = 0
    var shift = 0
    var pos = offset
    while (true) {
        require(pos < bytes.size) { "Truncated varint at offset $offset" }
        val b = bytes[pos].toInt() and 0xFF
        result = result or ((b and 0x7F) shl shift)
        pos++
        if (b and 0x80 == 0) break
        shift += 7
        require(shift < 32) { "Varint too long (overflows Int)" }
    }
    return result to (pos - offset)
}

// endregion

// region ktor length-delimited framing (consumed by sockets in Phase 2)

/**
 * Writes [bytes] length-prefixed with a varint, then flushes.
 * Length-delimited framing over a ktor [ByteWriteChannel].
 */
suspend fun ByteWriteChannel.writeDelimited(bytes: ByteArray) {
    val prefix = writeVarint(bytes.size)
    writeFully(prefix, 0, prefix.size)
    writeFully(bytes, 0, bytes.size)
    flush()
}

/**
 * Reads a varint length prefix then exactly that many body bytes.
 * Length-delimited framing over a ktor [ByteReadChannel].
 */
suspend fun ByteReadChannel.readDelimited(): ByteArray {
    var length = 0
    var shift = 0
    while (true) {
        val b = readByte().toInt() and 0xFF
        length = length or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        require(shift < 32) { "Varint too long (overflows Int)" }
    }
    val body = ByteArray(length)
    readFully(body, 0, length)
    return body
}

// endregion
