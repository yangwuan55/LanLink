package com.example.lanchat.data.socket

interface SocketCallback {
    fun onConnected()
    fun onDisconnected()
    fun onMessageReceived(message: ByteArray)
    fun onError(error: Throwable)
}