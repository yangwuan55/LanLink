package com.example.lanchat.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lanchat.R
import com.example.lanchat.data.repository.LanRepository
import com.example.lanchat.domain.model.ConnectionState
import com.example.lanchat.domain.model.PeerInfo
import com.example.lanchat.service.LanForegroundService
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: LanViewModel
    private var lanService: LanForegroundService? = null
    private var serviceBound = false
    private var repository: LanRepository? = null

    // UI components
    private lateinit var tabLayout: TabLayout
    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var messageLog: RecyclerView
    private lateinit var peerList: ListView
    private lateinit var progressBar: ProgressBar

    private var isServerMode = true
    private val messageAdapter = MessageAdapter()
    private lateinit var peerAdapter: PeerAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LanForegroundService.LocalBinder
            lanService = binder.getService()
            serviceBound = true
            repository?.let { lanService?.setRepository(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lanService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initializeApp()
        } else {
            Toast.makeText(this, "Permissions required for LAN discovery", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupRecyclerViews()
        checkPermissions()
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.tab_layout)
        statusText = findViewById(R.id.status_text)
        startStopButton = findViewById(R.id.start_stop_button)
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        messageLog = findViewById(R.id.message_log)
        peerList = findViewById(R.id.peer_list)
        progressBar = findViewById(R.id.progress_bar)

        peerAdapter = PeerAdapter { peer -> onPeerSelected(peer) }
    }

    private fun setupRecyclerViews() {
        messageLog.layoutManager = LinearLayoutManager(this)
        messageLog.adapter = messageAdapter
        peerList.adapter = peerAdapter
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isServerMode = tab?.position == 0
                updateUiForMode()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupClickListeners() {
        startStopButton.setOnClickListener {
            if (isServerMode) {
                val state = viewModel.connectionState.value
                if (state is ConnectionState.Connected ||
                    (state is ConnectionState.Idle && viewModel.uiState.value.statusMessage != "Starting server...")) {
                    viewModel.stopServer()
                    LanForegroundService.stop(this)
                } else {
                    viewModel.startServer()
                    LanForegroundService.start(this)
                    bindService()
                }
            } else {
                if (viewModel.connectionState.value is ConnectionState.Discovering) {
                    viewModel.stopDiscovery()
                } else {
                    viewModel.startDiscovery()
                }
            }
        }

        sendButton.setOnClickListener {
            val message = messageInput.text.toString()
            if (message.isNotBlank()) {
                viewModel.sendMessage(message)
                messageInput.text.clear()
            }
        }
    }

    private fun updateUiForMode() {
        if (isServerMode) {
            peerList.visibility = ListView.GONE
            startStopButton.text = if (viewModel.connectionState.value is ConnectionState.Connected) "Stop Server" else "Start Server"
        } else {
            peerList.visibility = ListView.VISIBLE
            startStopButton.text = if (viewModel.connectionState.value is ConnectionState.Discovering) "Stop Discovery" else "Start Discovery"
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            initializeApp()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun initializeApp() {
        viewModel = ViewModelProvider(this)[LanViewModel::class.java]
        viewModel.initialize()

        repository = LanRepository(applicationContext)

        // Observe state changes using lifecycleScope
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
            }
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Connected -> {
                progressBar.visibility = ProgressBar.GONE
                startStopButton.isEnabled = true
            }
            is ConnectionState.Connecting, is ConnectionState.Discovering -> {
                progressBar.visibility = ProgressBar.VISIBLE
                startStopButton.isEnabled = true
            }
            else -> {
                progressBar.visibility = ProgressBar.GONE
                startStopButton.isEnabled = true
            }
        }
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