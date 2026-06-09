package com.ymr.lanlink.core.data.socket

import com.ymr.lanlink.core.platform.nowMillis

data class SocketMessage(
    val payload: ByteArray,
    val timestamp: Long = nowMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SocketMessage) return false
        return payload.contentEquals(other.payload) && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}