package com.example.lanchat.data.socket

import java.io.InputStream
import java.io.OutputStream

/**
 * Callback interface for socket events.
 * Called on I/O threads.
 */
interface SocketCallback {
    fun onConnected(inputStream: InputStream, outputStream: OutputStream)
    fun onDisconnected()
    fun onMessageReceived(message: ByteArray)
    fun onError(error: Throwable)
}