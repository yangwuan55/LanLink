package com.example.lanchat.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lanchat.data.repository.LanRepository
import com.ymr.lancomm.data.auth.InMemoryAuthProvider
import com.ymr.lancomm.domain.auth.AuthProvider
import com.ymr.lancomm.domain.model.ConnectionState
import com.ymr.lancomm.domain.model.LanMessage
import com.ymr.lancomm.domain.model.PeerInfo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LanViewModel(application: Application) : AndroidViewModel(application) {

    enum class Role {
        Server, Client
    }

    sealed class AuthState {
        object Idle : AuthState()
        object Authenticating : AuthState()
        object AuthSuccess : AuthState()
        data class AuthFailed(val reason: String) : AuthState()
    }

    private var repository: LanRepository? = null
    private var isTestMode = false

    /**
     * For testing only - allows injecting a mock repository
     */
    fun setRepositoryForTesting(repository: LanRepository) {
        isTestMode = true
        this.repository = repository
        observeRepositoryState()
    }

    /**
     * For testing only - allows injecting flows directly
     */
    fun setRepositoryForTesting(
        connectionStateFlow: MutableStateFlow<ConnectionState>,
        discoveredPeersFlow: MutableStateFlow<List<PeerInfo>>,
        messagesFlow: MutableStateFlow<List<LanMessage>>
    ) {
        isTestMode = true
        viewModelScope.launch {
            connectionStateFlow.collect { state ->
                _connectionState.value = state
                updateUiStateFromConnection(state)
            }
        }
        viewModelScope.launch {
            discoveredPeersFlow.collect { peers ->
                _discoveredPeers.value = peers
            }
        }
        viewModelScope.launch {
            messagesFlow.collect { messageList ->
                messageList.forEach { domainMessage ->
                    _messages.value = _messages.value + domainMessage
                }
            }
        }
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerInfo>> = _discoveredPeers.asStateFlow()

    private val _messages = MutableStateFlow<List<LanMessage>>(emptyList())
    val messages: StateFlow<List<LanMessage>> = _messages.asStateFlow()

    private val _sharedSecret = MutableStateFlow("")
    val sharedSecret: StateFlow<String> = _sharedSecret.asStateFlow()

    private val _selectedRole = MutableStateFlow(Role.Client)
    val selectedRole: StateFlow<Role> = _selectedRole.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    data class UiState(
        val isServerMode: Boolean = false,
        val statusMessage: String = "Ready",
        val errorMessage: String? = null
    )

    fun initialize(authProvider: AuthProvider? = null) {
        if (isTestMode) return
        val context = getApplication<Application>().applicationContext
        repository = LanRepository(context, authProvider ?: com.ymr.lancomm.data.auth.NoOpAuthProvider())
        observeRepositoryState()
    }

    fun updateSharedSecret(secret: String) {
        _sharedSecret.value = secret
    }

    private fun observeRepositoryState() {
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
                    payload = protoMessage.payload.toStringUtf8()
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
            _authState.value = AuthState.Authenticating
            val secret = _sharedSecret.value.ifEmpty { "1234" }
            val authProvider = InMemoryAuthProvider(secret)
            val context = getApplication<Application>().applicationContext
            repository = LanRepository(context, authProvider)
            observeRepositoryState()
            repository?.startServer()
        }
    }

    fun stopServer() {
        repository?.stopServer()
        _uiState.update { it.copy(isServerMode = false, statusMessage = "Server stopped") }
        _authState.value = AuthState.Idle
    }

    // Client actions
    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isServerMode = false, statusMessage = "Starting discovery...") }
            _authState.value = AuthState.Authenticating
            val secret = _sharedSecret.value.ifEmpty { "1234" }
            val authProvider = InMemoryAuthProvider(secret)
            val context = getApplication<Application>().applicationContext
            repository = LanRepository(context, authProvider)
            observeRepositoryState()
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