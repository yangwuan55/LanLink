package com.example.lanchat.data.socket

import org.junit.Assert.*
import org.junit.Test

class SocketMessageTest {
    @Test
    fun `SocketMessage has timestamp and payload`() {
        val payload = "test data".toByteArray()
        val message = SocketMessage(payload = payload)
        assertTrue(message.timestamp > 0)
        assertTrue(message.payload.contentEquals(payload))
    }

    @Test
    fun `SocketMessage equals when payload and timestamp match`() {
        val payload = "test".toByteArray()
        val msg1 = SocketMessage(payload = payload, timestamp = 1000)
        val msg2 = SocketMessage(payload = payload, timestamp = 1000)
        assertEquals(msg1, msg2)
    }
}