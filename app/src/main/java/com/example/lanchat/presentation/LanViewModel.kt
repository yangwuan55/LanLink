package com.example.lanchat.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lanchat.data.repository.LanRepository
import com.example.lanchat.domain.auth.AuthProvider
import com.example.lanchat.domain.model.ConnectionState
import com.example.lanchat.domain.model.LanMessage
import com.example.lanchat.domain.model.PeerInfo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LanViewModel(application: Application) : AndroidViewModel(application) {

    private var repository: LanRepository? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerInfo>> = _discoveredPeers.asStateFlow()

    private val _messages = MutableStateFlow<List<LanMessage>>(emptyList())
    val messages: StateFlow<List<LanMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    data class UiState(
        val isServerMode: Boolean = false,
        val statusMessage: String = "Ready",
        val errorMessage: String? = null
    )

    fun initialize(authProvider: AuthProvider? = null) {
        val context = getApplication<Application>().applicationContext
        repository = LanRepository(context, authProvider ?: com.example.lanchat.data.auth.NoOpAuthProvider())

        // Observe repository state
        viewModelScope.launch {
            repository!!.connectionState.collect { state ->
                _connectionState.value = state
                updateUiStateFromConnection(state)
            }
        }

        viewModelScope.launch {
            repository!!.discoveredPeers.collect { peers ->
                _discoveredPeers.value = peers
            }
        }

        viewModelScope.launch {
            repository!!.messages.collect { protoMessage ->
                val domainMessage = LanMessage(
                    id = protoMessage.id,
                    timestamp = protoMessage.timestamp,
                    payload = protoMessage.payload
                )
                _messages.value = _messages.value + domainMessage
            }
        }
    }

    private fun updateUiStateFromConnection(state: ConnectionState) {
        _uiState.update {
            it.copy(
                statusMessage = when (state) {
                    is ConnectionState.Idle -> "Ready"
                    is ConnectionState.Discovering -> "Discovering..."
                    is ConnectionState.Connecting -> "Connecting..."
                    is ConnectionState.Connected -> "Connected to ${state.peerName}"
                    is ConnectionState.Error -> "Error: ${state.message}"
                },
                errorMessage = if (state is ConnectionState.Error) state.message else null
            )
        }
    }

    // Server actions
    fun startServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isServerMode = true, statusMessage = "Starting server...") }
            repository?.startServer()
        }
    }

    fun stopServer() {
        repository?.stopServer()
        _uiState.update { it.copy(isServerMode = false, statusMessage = "Server stopped") }
    }

    // Client actions
    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isServerMode = false, statusMessage = "Starting discovery...") }
            repository?.startDiscovery()
        }
    }

    fun stopDiscovery() {
        repository?.stopDiscovery()
        _uiState.update { it.copy(statusMessage = "Discovery stopped") }
    }

    fun connectToPeer(peer: PeerInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Connecting to ${peer.name}...") }
            repository?.connectToPeer(peer)
        }
    }

    fun disconnect() {
        repository?.disconnect()
        _uiState.update { it.copy(statusMessage = "Disconnected") }
    }

    // Messaging
    fun sendMessage(payload: String) {
        viewModelScope.launch {
            try {
                repository?.sendMessage(payload)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Send failed: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository?.stopServer()
        repository?.disconnect()
    }
}