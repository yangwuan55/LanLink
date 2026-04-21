package com.ymr.lancomm.data.nsd

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class NsdRegistrationState {
    object Idle : NsdRegistrationState()
    object Registering : NsdRegistrationState()
    data class Registered(val serviceName: String, val port: Int) : NsdRegistrationState()
    data class Error(val message: String) : NsdRegistrationState()
}

class NsdAdvertiser(private val context: Context) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String? = null

    private val _registrationState = MutableStateFlow<NsdRegistrationState>(NsdRegistrationState.Idle)
    val registrationState: StateFlow<NsdRegistrationState> = _registrationState.asStateFlow()

    companion object {
        private const val TAG = "NsdAdvertiser"
        private const val SERVICE_TYPE = "_lanchat._tcp."
    }

    fun registerService(serviceName: String, port: Int) {
        // Acquire MulticastLock BEFORE registration
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                this@NsdAdvertiser.serviceName = serviceInfo.serviceName
                _registrationState.value = NsdRegistrationState.Registered(serviceInfo.serviceName, port)
                Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _registrationState.value = NsdRegistrationState.Error("Registration failed: $errorCode")
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                _registrationState.value = NsdRegistrationState.Idle
                Log.d(TAG, "Service unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        _registrationState.value = NsdRegistrationState.Registering
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let { listener ->
            nsdManager.unregisterService(listener)
        }
        registrationListener = null
        releaseMulticastLock()
        _registrationState.value = NsdRegistrationState.Idle
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("NsdAdvertiser").apply {
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