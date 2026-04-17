package com.example.lanchat.domain.model

import org.junit.Assert.*
import org.junit.Test

class LanMessageTest {
    @Test
    fun `LanMessage has default id and timestamp`() {
        val message = LanMessage(payload = "Hello")
        assertNotNull(message.id)
        assertTrue(message.id.isNotEmpty())
        assertTrue(message.timestamp > 0)
    }

    @Test
    fun `LanMessage preserves payload`() {
        val payload = "Test message content"
        val message = LanMessage(payload = payload)
        assertEquals(payload, message.payload)
    }

    @Test
    fun `LanMessage id is unique`() {
        val msg1 = LanMessage(payload = "test")
        val msg2 = LanMessage(payload = "test")
        assertNotEquals(msg1.id, msg2.id)
    }
}