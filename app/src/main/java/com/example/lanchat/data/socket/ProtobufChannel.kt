package com.example.lanchat.data.socket

import android.util.Log
import com.example.lanchat.proto.AuthRequest
import com.example.lanchat.proto.AuthResponse
import com.example.lanchat.proto.LanMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Protobuf channel with length-prefixed framing.
 * Each message: [4-byte length][protobuf payload]
 */
class ProtobufChannel(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    companion object {
        private const val TAG = "ProtobufChannel"
        private const val LENGTH_BYTES = 4
        private const val MAX_MESSAGE_SIZE = 1024 * 1024  // 1MB max
    }

    /**
     * Send a LanMessage
     */
    suspend fun send(message: LanMessage) = withContext(Dispatchers.IO) {
        try {
            val payload = message.toByteArray()
            val frame = createFrame(payload)
            outputStream.write(frame)
            outputStream.flush()
            Log.d(TAG, "Sent LanMessage: ${message.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending LanMessage", e)
            throw e
        }
    }

    /**
     * Receive a LanMessage
     */
    suspend fun receiveLanMessage(): LanMessage = withContext(Dispatchers.IO) {
        try {
            val payload = readFrame()
            val message = LanMessage.parseFrom(payload)
            Log.d(TAG, "Received LanMessage: ${message.id}")
            message
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving LanMessage", e)
            throw e
        }
    }

    /**
     * Send AuthRequest
     */
    suspend fun sendAuthRequest(request: AuthRequest) = withContext(Dispatchers.IO) {
        try {
            val payload = request.toByteArray()
            val frame = createFrame(payload)
            outputStream.write(frame)
            outputStream.flush()
            Log.d(TAG, "Sent AuthRequest")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending AuthRequest", e)
            throw e
        }
    }

    /**
     * Receive AuthRequest
     */
    suspend fun receiveAuthRequest(): AuthRequest = withContext(Dispatchers.IO) {
        try {
            val payload = readFrame()
            AuthRequest.parseFrom(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving AuthRequest", e)
            throw e
        }
    }

    /**
     * Send AuthResponse
     */
    suspend fun sendAuthResponse(response: AuthResponse) = withContext(Dispatchers.IO) {
        try {
            val payload = response.toByteArray()
            val frame = createFrame(payload)
            outputStream.write(frame)
            outputStream.flush()
            Log.d(TAG, "Sent AuthResponse: success=${response.success}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending AuthResponse", e)
            throw e
        }
    }

    /**
     * Receive AuthResponse
     */
    suspend fun receiveAuthResponse(): AuthResponse = withContext(Dispatchers.IO) {
        try {
            val payload = readFrame()
            AuthResponse.parseFrom(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving AuthResponse", e)
            throw e
        }
    }

    /**
     * Create length-prefixed frame: [4-byte length][payload]
     */
    private fun createFrame(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(LENGTH_BYTES + payload.size)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Read length-prefixed frame and return payload
     */
    private fun readFrame(): ByteArray {
        // Read length prefix
        val lengthBuffer = ByteBuffer.allocate(LENGTH_BYTES)
        var bytesRead = 0
        while (bytesRead < LENGTH_BYTES) {
            val read = inputStream.read(lengthBuffer.array(), bytesRead, LENGTH_BYTES - bytesRead)
            if (read == -1) throw Exception("End of stream")
            bytesRead += read
        }
        lengthBuffer.flip()
        val length = lengthBuffer.int

        if (length <= 0 || length > MAX_MESSAGE_SIZE) {
            throw Exception("Invalid message length: $length")
        }

        // Read payload
        val payload = ByteArray(length)
        bytesRead = 0
        while (bytesRead < length) {
            val read = inputStream.read(payload, bytesRead, length - bytesRead)
            if (read == -1) throw Exception("End of stream")
            bytesRead += read
        }

        return payload
    }
}