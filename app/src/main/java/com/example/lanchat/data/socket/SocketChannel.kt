package com.example.lanchat.data.socket

import java.io.InputStream
import java.io.OutputStream

/**
 * Abstraction for socket read/write operations.
 * Actual implementation deferred to TcpSocketServer and TcpSocketClient.
 */
interface SocketChannel {
    val inputStream: InputStream
    val outputStream: OutputStream
    val isConnected: Boolean

    suspend fun read(): ByteArray
    suspend fun write(data: ByteArray)
    fun close()
}