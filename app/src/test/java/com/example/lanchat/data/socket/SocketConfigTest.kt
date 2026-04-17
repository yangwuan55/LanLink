package com.example.lanchat.data.socket

import org.junit.Assert.*
import org.junit.Test

class SocketConfigTest {
    @Test
    fun `SocketConfig has default timeout values`() {
        val config = SocketConfig(host = "localhost", port = 8080)
        assertEquals("localhost", config.host)
        assertEquals(8080, config.port)
        assertEquals(5000L, config.connectTimeoutMs)
        assertEquals(0L, config.readTimeoutMs)
    }

    @Test
    fun `SocketConfig accepts custom timeout`() {
        val config = SocketConfig(host = "192.168.1.1", port = 9000, connectTimeoutMs = 10000)
        assertEquals(10000L, config.connectTimeoutMs)
    }
}