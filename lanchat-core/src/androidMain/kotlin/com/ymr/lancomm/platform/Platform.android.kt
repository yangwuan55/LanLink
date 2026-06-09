package com.ymr.lancomm.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.security.SecureRandom

private val secureRandom = SecureRandom()

// Build.MODEL is a platform String and may be null (e.g. on the host JVM test
// runtime where Android stubs return null); fall back so callers never NPE.
actual fun deviceName(): String = android.os.Build.MODEL ?: "Android"

actual fun nowMillis(): Long = System.currentTimeMillis()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }

actual fun localIpv4Address(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (_: Exception) {
        // fall through to default
    }
    return "127.0.0.1"
}
