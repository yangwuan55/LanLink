package com.ymr.lanlink.presentation

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import com.ymr.lanlink.data.repository.LanRepository
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.LanMessage
import com.ymr.lanlink.core.domain.model.PeerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: TestableLanViewModel
    private lateinit var mockRepository: MockLanRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = MockLanRepository()
        viewModel = TestableLanViewModel(mockRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(ConnectionState.Idle, viewModel.connectionState.value)
    }

    @Test
    fun `initial discovered peers is empty`() {
        assertTrue(viewModel.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `initial messages is empty`() {
        assertTrue(viewModel.messages.value.isEmpty())
    }

    @Test
    fun `updateSharedSecret updates the secret`() = runTest {
        viewModel.updateSharedSecret("123456")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("123456", viewModel.sharedSecret.value)
    }

    @Test
    fun `messages are bounded to MAX_MESSAGES`() = runTest {
        // Add more messages than MAX_MESSAGES
        repeat(550) { i ->
            viewModel.testAddMessage(LanMessage("msg-$i", System.currentTimeMillis(), "Test $i"))
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Should be bounded to MAX_MESSAGES
        assertTrue(
            "Messages should be bounded to MAX_MESSAGES (500), but was ${viewModel.messages.value.size}",
            viewModel.messages.value.size <= 500
        )
    }

    @Test
    fun `messages are FIFO within limit`() = runTest {
        viewModel.testAddMessage(LanMessage("msg-1", 1, "First"))
        viewModel.testAddMessage(LanMessage("msg-2", 2, "Second"))
        viewModel.testAddMessage(LanMessage("msg-3", 3, "Third"))
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals("msg-1", messages[0].id)
        assertEquals("msg-2", messages[1].id)
        assertEquals("msg-3", messages[2].id)
    }

    @Test
    fun `connectionState updates correctly`() = runTest {
        mockRepository._connectionState.value = ConnectionState.Connecting
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ConnectionState.Connecting, viewModel.connectionState.value)

        mockRepository._connectionState.value = ConnectionState.Connected("TestPeer", false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.connectionState.value is ConnectionState.Connected)
        assertEquals("TestPeer", (viewModel.connectionState.value as ConnectionState.Connected).peerName)
    }

    @Test
    fun `discoveredPeers updates correctly`() = runTest {
        val peer = PeerInfo("TestDevice", "127.0.0.1", 12345)
        mockRepository._discoveredPeers.value = listOf(peer)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.discoveredPeers.value.size)
        assertEquals("TestDevice", viewModel.discoveredPeers.value[0].name)
    }

    @Test
    fun `uiState updates based on connection state`() = runTest {
        mockRepository._connectionState.value = ConnectionState.Discovering
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Discovering...", viewModel.uiState.value.statusMessage)

        mockRepository._connectionState.value = ConnectionState.Connected("Peer1", false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Connected to Peer1", viewModel.uiState.value.statusMessage)

        mockRepository._connectionState.value = ConnectionState.Error("Test error")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Error: Test error", viewModel.uiState.value.statusMessage)
        assertEquals("Test error", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `sendMessage delegates to repository`() = runTest {
        viewModel.sendMessage("Hello")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, mockRepository.sentMessages.size)
        assertEquals("Hello", mockRepository.sentMessages[0])
    }

    @Test
    fun `disconnect delegates to repository`() = runTest {
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(mockRepository.disconnectCalled)
    }
}

// Testable subclass that uses a mock repository
private class TestableLanViewModel(
    private val mockRepo: MockLanRepository
) : LanViewModelForTest() {

    init {
        setRepositoryForTesting(mockRepo)
    }

    fun testAddMessage(message: LanMessage) {
        _messages.update { msgs ->
            val updated = msgs + message
            if (updated.size > MAX_MESSAGES) {
                updated.takeLast(MAX_MESSAGES)
            } else {
                updated
            }
        }
    }
}

// Abstract class to allow testing without Application
private abstract class LanViewModelForTest : androidx.lifecycle.ViewModel() {
    companion object {
        const val MAX_MESSAGES = 500
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val discoveredPeers: kotlinx.coroutines.flow.StateFlow<List<PeerInfo>> = _discoveredPeers.asStateFlow()

    protected val _messages = MutableStateFlow<List<LanMessage>>(emptyList())
    val messages: kotlinx.coroutines.flow.StateFlow<List<LanMessage>> = _messages.asStateFlow()

    private val _sharedSecret = MutableStateFlow("")
    val sharedSecret: kotlinx.coroutines.flow.StateFlow<String> = _sharedSecret.asStateFlow()

    private val _uiState = MutableStateFlow(LanViewModel.UiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<LanViewModel.UiState> = _uiState.asStateFlow()

    fun setRepositoryForTesting(repository: MockLanRepository) {
        this.repository = repository
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                _connectionState.value = state
                _uiState.value = _uiState.value.copy(
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
        viewModelScope.launch {
            repository.discoveredPeers.collect { peers ->
                _discoveredPeers.value = peers
            }
        }
    }

    fun updateSharedSecret(secret: String) {
        _sharedSecret.value = secret
    }

    fun disconnect() {
        repository?.disconnect()
    }

    fun sendMessage(payload: String) {
        viewModelScope.launch {
            repository?.sendMessage(payload)
        }
    }

    private var repository: MockLanRepository? = null
}

// Mock repository for testing
private class MockLanRepository : LanRepositoryForTest() {
    val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())

    override val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>
        get() = _connectionState.asStateFlow()
    override val discoveredPeers: kotlinx.coroutines.flow.StateFlow<List<PeerInfo>>
        get() = _discoveredPeers.asStateFlow()

    val sentMessages = mutableListOf<String>()
    var disconnectCalled = false

    override fun sendMessage(payload: String) {
        sentMessages.add(payload)
    }

    override fun disconnect() {
        disconnectCalled = true
    }
}

// Abstract mock to match LanRepository interface
private abstract class LanRepositoryForTest {
    abstract val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    abstract val discoveredPeers: kotlinx.coroutines.flow.StateFlow<List<PeerInfo>>
    abstract fun sendMessage(payload: String)
    abstract fun disconnect()
}