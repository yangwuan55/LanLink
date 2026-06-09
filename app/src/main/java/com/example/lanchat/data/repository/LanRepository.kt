package com.example.lanchat.data.repository

import android.util.Log
import com.ymr.lancomm.data.proto.LanMessage
import com.ymr.lancomm.data.proto.decodeLanMessage
import com.ymr.lancomm.data.proto.encode
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.service.PinConnectionService
import com.ymr.lancomm.service.PinConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LanRepository(private val service: PinConnectionService) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val connectionState: StateFlow<ConnectionState> = service.connectionState
        .map { pinState ->
            when (pinState) {
                is PinConnectionState.Idle -> ConnectionState.Idle
                is PinConnectionState.Discovering -> ConnectionState.Discovering
                is PinConnectionState.Connecting -> ConnectionState.Connecting
                is PinConnectionState.Connected -> ConnectionState.Connected(pinState.peerName, pinState.isServer)
                is PinConnectionState.Error -> ConnectionState.Error(pinState.reason)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    // The service hands us (type, bytes); the app decides how to parse each type.
    val messages: SharedFlow<LanMessage> = service.messageFlow
        .mapNotNull { typed ->
            when (typed.type) {
                TYPE_CHAT -> try {
                    decodeLanMessage(typed.payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse chat message", e)
                    null
                }
                else -> {
                    Log.w(TAG, "Ignoring unknown message type ${typed.type}")
                    null
                }
            }
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    fun startServer(pin: String) = service.startServer(pin)

    fun connectServer(pin: String) = service.connectServer(pin)

    fun disconnect() = service.disconnect()

    suspend fun sendMessage(payload: String, type: Int = TYPE_CHAT) {
        val message = LanMessage(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            payload = payload.encodeToByteArray()
        )
        service.send(type, message.encode())
    }

    fun close() {
        service.disconnect()
        scope.cancel()
    }

    companion object {
        private const val TAG = "LanRepository"
        const val TYPE_CHAT = 0
    }
}
