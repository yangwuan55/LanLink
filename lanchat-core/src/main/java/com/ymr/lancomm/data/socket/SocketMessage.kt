package com.ymr.lancomm.data.socket

data class SocketMessage(
    val payload: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SocketMessage
        return payload.contentEquals(other.payload) && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}