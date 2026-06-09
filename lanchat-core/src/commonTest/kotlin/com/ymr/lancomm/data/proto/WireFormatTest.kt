package com.ymr.lancomm.data.proto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Permanent wire-format regression for the kotlinx-serialization-protobuf models.
 *
 * Replaces the transient cross-parse test that ran once in Phase 1 (against the
 * Google runtime) to prove wire compatibility, then was removed together with that
 * dependency. These golden vectors are hand-encoded standard protobuf wire bytes,
 * so they pin the on-the-wire format with NO external runtime: any drift in field
 * numbers, wire types, varint, or length-delimited framing fails here.
 */
class WireFormatTest {

    // region round-trip: encode -> decode preserves every field

    @Test
    fun lanMessage_roundTrip() {
        val msg = LanMessage(
            id = "msg-1",
            timestamp = 1_700_000_000_000L,
            payload = byteArrayOf(1, 2, 3, 0, -1, 127, -128),
            type = 7,
        )
        val back = decodeLanMessage(msg.encode())
        assertEquals(msg.id, back.id)
        assertEquals(msg.timestamp, back.timestamp)
        assertContentEquals(msg.payload, back.payload)
        assertEquals(msg.type, back.type)
    }

    @Test
    fun authRequest_roundTrip_utf8() {
        val msg = AuthRequest(deviceName = "设备-😀", credentials = byteArrayOf(10, 20), customData = byteArrayOf(-1))
        val back = decodeAuthRequest(msg.encode())
        assertEquals(msg.deviceName, back.deviceName)
        assertContentEquals(msg.credentials, back.credentials)
        assertContentEquals(msg.customData, back.customData)
    }

    @Test
    fun authResponse_roundTrip() {
        val msg = AuthResponse(success = true, message = "ok-认证", customData = byteArrayOf(9, 8, 7))
        val back = decodeAuthResponse(msg.encode())
        assertEquals(msg.success, back.success)
        assertEquals(msg.message, back.message)
        assertContentEquals(msg.customData, back.customData)
    }

    @Test
    fun connectionStatus_roundTrip() {
        val msg = ConnectionStatus(connected = true, peerName = "peer-伙伴")
        val back = decodeConnectionStatus(msg.encode())
        assertEquals(msg.connected, back.connected)
        assertEquals(msg.peerName, back.peerName)
    }

    // endregion

    // region golden vectors: decode standard protobuf wire bytes (field-order independent)

    @Test
    fun decode_golden_lanMessage() {
        // f1 id="x": 0A 01 78 | f2 timestamp=5: 10 05 | f4 type=1: 20 01 (payload f3 omitted -> default empty)
        val wire = byteArrayOf(0x0A, 0x01, 0x78, 0x10, 0x05, 0x20, 0x01)
        val m = decodeLanMessage(wire)
        assertEquals("x", m.id)
        assertEquals(5L, m.timestamp)
        assertEquals(1, m.type)
        assertContentEquals(ByteArray(0), m.payload)
    }

    @Test
    fun decode_golden_lanMessage_multiByteVarint() {
        // f2 timestamp=300 -> varint AC 02 (300 = 0b1_0010_1100)
        val wire = byteArrayOf(0x10, 0xAC.toByte(), 0x02)
        assertEquals(300L, decodeLanMessage(wire).timestamp)
    }

    @Test
    fun decode_golden_authRequest() {
        // f1 deviceName="hi": 0A 02 68 69 | f2 credentials=[01 02]: 12 02 01 02
        val wire = byteArrayOf(0x0A, 0x02, 0x68, 0x69, 0x12, 0x02, 0x01, 0x02)
        val r = decodeAuthRequest(wire)
        assertEquals("hi", r.deviceName)
        assertContentEquals(byteArrayOf(0x01, 0x02), r.credentials)
    }

    @Test
    fun decode_golden_authResponse() {
        // f1 success=true: 08 01 | f2 message="OK": 12 02 4F 4B
        val wire = byteArrayOf(0x08, 0x01, 0x12, 0x02, 0x4F, 0x4B)
        val r = decodeAuthResponse(wire)
        assertEquals(true, r.success)
        assertEquals("OK", r.message)
    }

    // endregion

    // region golden vectors: encode messages with no bytes fields (unambiguous standard protobuf)

    @Test
    fun encode_golden_connectionStatus() {
        // ConnectionStatus has no bytes fields, so the encoding is unambiguous.
        assertContentEquals(byteArrayOf(0x08, 0x01), ConnectionStatus(connected = true).encode())      // f1 bool=1
        assertContentEquals(byteArrayOf(0x12, 0x01, 0x78), ConnectionStatus(peerName = "x").encode())   // f2 string "x"
        assertEquals(0, ConnectionStatus().encode().size)                                               // all defaults -> empty wire
    }

    @Test
    fun emptyByteArray_field_is_explicitlyEncoded_butDecodesToDefault() {
        // Documented quirk: kotlinx-serialization-protobuf emits an empty ByteArray as an
        // explicit zero-length field (tag + len 0), whereas proto3 omits it. Both still
        // decode to the same default — proven by size delta + round-trip here.
        val encoded = LanMessage(id = "x").encode()
        assertEquals(5, encoded.size) // f1 "x" (3B) + f3 empty payload explicit (2B); would be 3B if omitted
        val back = decodeLanMessage(encoded)
        assertEquals("x", back.id)
        assertContentEquals(ByteArray(0), back.payload)
    }

    // endregion
}
