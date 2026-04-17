package com.example.lanchat.data.nsd

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.lanchat.domain.model.PeerInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

sealed class NsdDiscoveryState {
    object Idle : NsdDiscoveryState()
    object Discovering : NsdDiscoveryState()
    object Stopped : NsdDiscoveryState()
    data class Error(val message: String) : NsdDiscoveryState()
}

class NsdDiscoverer(private val context: Context) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private val _discoveredPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerInfo>> = _discoveredPeers.asStateFlow()

    private val _discoveryState = MutableStateFlow<NsdDiscoveryState>(NsdDiscoveryState.Idle)
    val discoveryState: StateFlow<NsdDiscoveryState> = _discoveryState.asStateFlow()

    companion object {
        private const val TAG = "NsdDiscoverer"
        private const val SERVICE_TYPE = "_lanchat._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 30000L
    }

    suspend fun discoverServices(serviceType: String = SERVICE_TYPE) {
        // CRITICAL: Acquire MulticastLock BEFORE discovery
        acquireMulticastLock()

        _discoveryState.value = NsdDiscoveryState.Discovering
        _discoveredPeers.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                // CRITICAL: Must resolve service BEFORE accessing host/port
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                // Remove from discovered peers
                _discoveredPeers.value = _discoveredPeers.value.filter {
                    it.name != serviceInfo.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
                _discoveryState.value = NsdDiscoveryState.Stopped
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                _discoveryState.value = NsdDiscoveryState.Error("Discovery failed: $errorCode")
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        // Implement timeout
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            // Wait while discovering
            while (_discoveryState.value == NsdDiscoveryState.Discovering) {
                delay(1000)
            }
        }

        if (_discoveryState.value == NsdDiscoveryState.Discovering) {
            stopDiscovery()
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                // NOW host and port are available after resolveService
                val peer = PeerInfo(
                    name = serviceInfo.serviceName,
                    host = serviceInfo.host,
                    port = serviceInfo.port
                )
                // Filter out self (same device)
                val currentPeers = _discoveredPeers.value.toMutableList()
                currentPeers.removeAll { it.name == serviceInfo.serviceName }
                currentPeers.add(peer)
                _discoveredPeers.value = currentPeers
            }
        }

        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        _discoveryState.value = NsdDiscoveryState.Stopped
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("NsdDiscoverer").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            it.release()
            multicastLock = null
            Log.d(TAG, "MulticastLock released")
        }
    }
}