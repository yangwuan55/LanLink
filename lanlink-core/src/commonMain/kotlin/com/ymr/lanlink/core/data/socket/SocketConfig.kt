package com.ymr.lanlink.core.data.socket

data class SocketConfig(
    val host: String,
    val port: Int,
    val connectTimeoutMs: Long = 5000,
    val readTimeoutMs: Long = 0  // 0 = no timeout
)