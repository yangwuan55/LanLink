package com.ymr.lanlink.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ymr.lanlink.data.repository.LanRepository
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.LanMessage
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.core.net.android.AndroidLanNetworkFactory
import com.ymr.lanlink.core.service.PinConnectionServiceImpl
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val MAX_MESSAGES = 500

class LanViewModel(application: Application) : AndroidViewModel(application) {

    enum class Role { Server, Client }

    sealed class AuthState {
        object Idle : AuthState()
        object Authenticating : AuthState()
        object AuthSuccess : AuthState()
        data class AuthFailed(val reason: String) : AuthState()
    }

    private var repository: LanRepository? = null
    private var isTestMode = false

    val currentRepository: LanRepository?
        get() = repository

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Kept for UI compatibility — always empty since PIN-based connection auto-connects
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

    fun initialize(repository: LanRepository? = null) {
        if (isTestMode) return
        if (repository != null) {
            this.repository = repository
        } else {
            val service = PinConnectionServiceImpl(AndroidLanNetworkFactory())
            this.repository = LanRepository(service)
        }
        observeRepositoryState()
    }

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
                    _messages.update { msgs ->
                        val updated = msgs + domainMessage
                        if (updated.size > MAX_MESSAGES) updated.takeLast(MAX_MESSAGES) else updated
                    }
                }
            }
        }
    }

    fun updateSharedSecret(secret: String) {
        _sharedSecret.value = secret
    }

    private fun observeRepositoryState() {
        val repo = repository ?: return

        viewModelScope.launch {
            repo.connectionState.collect { state ->
                _connectionState.value = state
                updateUiStateFromConnection(state)
            }
        }

        viewModelScope.launch {
            repo.messages.collect { protoMessage ->
                val domainMessage = LanMessage(
                    id = protoMessage.id,
                    timestamp = protoMessage.timestamp,
                    payload = protoMessage.payload.decodeToString()
                )
                _messages.update { msgs ->
                    val updated = msgs + domainMessage
                    if (updated.size > MAX_MESSAGES) updated.takeLast(MAX_MESSAGES) else updated
                }
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

    fun startServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isServerMode = true, statusMessage = "Starting server...") }
            _authState.value = AuthState.Authenticating
            repository?.startServer(_sharedSecret.value)
        }
    }

    fun stopServer() {
        repository?.disconnect()
        _uiState.update { it.copy(isServerMode = false, statusMessage = "Server stopped") }
        _authState.value = AuthState.Idle
    }

    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isServerMode = false, statusMessage = "Connecting...") }
            _authState.value = AuthState.Authenticating
            repository?.connectServer(_sharedSecret.value)
        }
    }

    fun stopDiscovery() {
        repository?.disconnect()
        _uiState.update { it.copy(statusMessage = "Stopped") }
    }

    fun startMatching(isServer: Boolean): LanRepository {
        _uiState.update { it.copy(isServerMode = isServer, statusMessage = "Starting matching...") }
        _authState.value = AuthState.Authenticating
        val repo = repository ?: run {
            val service = PinConnectionServiceImpl(AndroidLanNetworkFactory())
            LanRepository(service).also {
                repository = it
                observeRepositoryState()
            }
        }
        viewModelScope.launch {
            if (isServer) {
                repo.startServer(_sharedSecret.value)
            } else {
                repo.connectServer(_sharedSecret.value)
            }
        }
        return repo
    }

    fun connectToPeer(peer: PeerInfo) {
        // No-op: PIN-based connection handles peer selection automatically
    }

    fun disconnect() {
        repository?.disconnect()
        _uiState.update { it.copy(statusMessage = "Disconnected") }
    }

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
        repository?.close()
    }
}
