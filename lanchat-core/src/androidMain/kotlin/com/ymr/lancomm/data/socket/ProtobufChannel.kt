package com.ymr.lancomm.data.socket

import android.util.Log
import com.ymr.lancomm.proto.AuthRequest
import com.ymr.lancomm.proto.AuthResponse
import com.ymr.lancomm.proto.LanMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class ProtobufChannel(
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) {
    companion object {
        private const val TAG = "ProtobufChannel"
    }

    suspend fun sendAuthRequest(request: AuthRequest) = withContext(Dispatchers.IO) {
        try {
            request.writeDelimitedTo(outputStream)
            outputStream.flush()
            Log.d(TAG, "Sent AuthRequest")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending AuthRequest", e)
            throw e
        }
    }

    suspend fun receiveAuthRequest(): AuthRequest = withContext(Dispatchers.IO) {
        try {
            val request = AuthRequest.parseDelimitedFrom(inputStream)
            Log.d(TAG, "Received AuthRequest")
            request
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving AuthRequest", e)
            throw e
        }
    }

    suspend fun sendAuthResponse(response: AuthResponse) = withContext(Dispatchers.IO) {
        try {
            response.writeDelimitedTo(outputStream)
            outputStream.flush()
            Log.d(TAG, "Sent AuthResponse: success=${response.success}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending AuthResponse", e)
            throw e
        }
    }

    suspend fun receiveAuthResponse(): AuthResponse = withContext(Dispatchers.IO) {
        try {
            val response = AuthResponse.parseDelimitedFrom(inputStream)
            Log.d(TAG, "Received AuthResponse")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving AuthResponse", e)
            throw e
        }
    }

    suspend fun send(message: LanMessage) = withContext(Dispatchers.IO) {
        try {
            message.writeDelimitedTo(outputStream)
            outputStream.flush()
            Log.d(TAG, "Sent LanMessage: ${message.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending LanMessage", e)
            throw e
        }
    }

    suspend fun receiveLanMessage(): LanMessage = withContext(Dispatchers.IO) {
        try {
            val message = LanMessage.parseDelimitedFrom(inputStream)
            Log.d(TAG, "Received LanMessage: ${message.id}")
            message
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving LanMessage", e)
            throw e
        }
    }
}