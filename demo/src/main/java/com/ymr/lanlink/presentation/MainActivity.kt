package com.ymr.lanlink.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ymr.lanlink.R
import com.ymr.lanlink.core.domain.model.ConnectionState
import com.ymr.lanlink.core.domain.model.PeerInfo
import com.ymr.lanlink.service.LanForegroundService
import android.text.Editable
import android.text.TextWatcher
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"
private const val SHARED_SECRET_LENGTH = 6

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: LanViewModel
    private var lanService: LanForegroundService? = null
    private var serviceBound = false

    // UI components
    private lateinit var roleSelection: RadioGroup
    private lateinit var secretKeyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var pairingButton: Button
    private lateinit var directConnectButton: Button
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var messageLog: RecyclerView
    private lateinit var peerList: ListView
    private lateinit var peerListContainer: LinearLayout
    private lateinit var progressBar: ProgressBar

    private var isServerMode = true
    private var currentSecretKey = ""
    private val messageAdapter = MessageAdapter()
    private lateinit var peerAdapter: PeerAdapter

    private fun hasValidSharedSecret(): Boolean {
        return currentSecretKey.length == SHARED_SECRET_LENGTH && currentSecretKey.all { it.isDigit() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LanForegroundService.LocalBinder
            lanService = binder.getService()
            serviceBound = true
            viewModel.currentRepository?.let { lanService?.setRepository(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lanService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerViews()
        initializeApp()
    }

    private fun initViews() {
        roleSelection = findViewById(R.id.role_selection)
        secretKeyInput = findViewById(R.id.secret_key_input)
        statusText = findViewById(R.id.status_text)
        startStopButton = findViewById(R.id.start_stop_button)
        pairingButton = findViewById(R.id.pairing_button)
        directConnectButton = findViewById(R.id.direct_connect_button)
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        messageLog = findViewById(R.id.message_log)
        peerList = findViewById(R.id.peer_list)
        peerListContainer = findViewById(R.id.peer_list_container)
        progressBar = findViewById(R.id.progress_bar)

        peerAdapter = PeerAdapter { peer -> onPeerSelected(peer) }
    }

    private fun setupRecyclerViews() {
        messageLog.layoutManager = LinearLayoutManager(this)
        messageLog.adapter = messageAdapter
        peerList.adapter = peerAdapter
    }

    private fun setupRoleSelection() {
        roleSelection.setOnCheckedChangeListener { _, checkedId ->
            isServerMode = checkedId == R.id.radio_server
            updateUiForMode()
        }
    }

    private fun setupSecretKeyInput() {
        secretKeyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSecretKey = s?.toString() ?: ""
                viewModel.updateSharedSecret(currentSecretKey)
                updateUiForMode()
            }
        })
    }

    private fun ensureSession() {
        // Lazily create the repository + foreground service the first time the
        // user starts an action.
        if (viewModel.currentRepository == null) {
            val repository = viewModel.startMatching(isServerMode)
            LanForegroundService.start(this)
            bindService()
            lanService?.setRepository(repository)
        }
    }

    private fun setupClickListeners() {
        Log.d(TAG, "Setting up click listeners")
        // Server: 启动服务 / 停止服务. Client: 配对连接 (pairWithServer) / 停止.
        startStopButton.setOnClickListener {
            Log.d(TAG, "Start/Stop button clicked, isServerMode: $isServerMode")
            if (isServerMode) {
                val state = viewModel.connectionState.value
                if (state is ConnectionState.Idle) {
                    lifecycleScope.launch { viewModel.startServer() }
                    LanForegroundService.start(this)
                    bindService()
                } else {
                    viewModel.stopServer()
                    LanForegroundService.stop(this)
                }
            } else {
                if (viewModel.connectionState.value is ConnectionState.Discovering ||
                    viewModel.connectionState.value is ConnectionState.Connecting
                ) {
                    viewModel.stopDiscovery()
                } else if (hasValidSharedSecret()) {
                    ensureSession()
                    lifecycleScope.launch { viewModel.startDiscovery() }
                } else {
                    Toast.makeText(this, "请输入 6 位 PIN", Toast.LENGTH_SHORT).show()
                }
            }
            updateUiForMode()
        }

        // Server only: 开启配对 (输 PIN) / 关闭配对.
        pairingButton.setOnClickListener {
            if (!hasValidSharedSecret()) {
                Toast.makeText(this, "请输入 6 位 PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (viewModel.pairingActive.value) {
                viewModel.stopPairing()
            } else {
                viewModel.startPairing()
            }
            updateUiForMode()
        }

        // Client only: 重连 (reconnectLastServer, no PIN).
        directConnectButton.setOnClickListener {
            Log.d(TAG, "Reconnect button clicked")
            if (viewModel.connectionState.value is ConnectionState.Idle) {
                ensureSession()
                viewModel.reconnectLastServer()
            }
        }

        sendButton.setOnClickListener {
            val message = messageInput.text.toString()
            if (message.isNotBlank()) {
                Log.d(TAG, "Send button clicked, message: $message")
                viewModel.sendMessage(message)
                messageInput.text.clear()
            }
        }
    }

    private fun updateUiForMode() {
        if (!::viewModel.isInitialized) return

        Log.d(TAG, "Updating UI for mode: ${if (isServerMode) "Server" else "Client"}")
        val state = viewModel.connectionState.value
        val serverRunning = state !is ConnectionState.Idle && state !is ConnectionState.Error
        if (isServerMode) {
            peerListContainer.isVisible = false
            directConnectButton.isVisible = false
            // 启动服务 / 停止服务
            startStopButton.text = if (serverRunning) "停止服务" else "启动服务"
            // 开启配对 / 关闭配对 — only meaningful once the server is running.
            pairingButton.isVisible = serverRunning
            pairingButton.text = if (viewModel.pairingActive.value) "关闭配对" else "开启配对（输 PIN）"
        } else {
            peerListContainer.isVisible = true
            pairingButton.isVisible = false
            // 重连 — visible when a stored credential exists.
            directConnectButton.isVisible = viewModel.hasPairingCredential.value
            // 配对连接 / 停止
            startStopButton.text = when (state) {
                is ConnectionState.Discovering, is ConnectionState.Connecting -> "停止"
                else -> "配对连接"
            }
        }
    }

    private fun initializeApp() {
        Log.d(TAG, "Initializing app")
        viewModel = ViewModelProvider(this)[LanViewModel::class.java]
        viewModel.initialize()

        setupRoleSelection()
        setupSecretKeyInput()
        setupClickListeners()
        Log.d(TAG, "App initialization complete")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionState(state)
                    }
                }
                launch {
                    viewModel.discoveredPeers.collect { peers ->
                        peerAdapter.submitList(peers)
                    }
                }
                launch {
                    viewModel.messages.collect { messages ->
                        messageAdapter.submitList(messages)
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        statusText.text = state.statusMessage
                        state.errorMessage?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        }
                        updateUiForMode()
                    }
                }
                launch {
                    viewModel.hasPairingCredential.collect {
                        // Reconnect button visibility depends on stored credential + client mode.
                        updateUiForMode()
                    }
                }
                launch {
                    viewModel.pairingActive.collect {
                        // Refresh the pairing button label when the window toggles.
                        updateUiForMode()
                    }
                }
            }
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        Log.d(TAG, "Connection state changed to: ${state::class.simpleName}")
        when (state) {
            is ConnectionState.Connecting, is ConnectionState.Discovering ->
                progressBar.visibility = ProgressBar.VISIBLE
            else ->
                progressBar.visibility = ProgressBar.GONE
        }
        startStopButton.isEnabled = true
        updateUiForMode()
    }

    private fun onPeerSelected(peer: PeerInfo) {
        viewModel.connectToPeer(peer)
    }

    private fun bindService() {
        Intent(this, LanForegroundService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
