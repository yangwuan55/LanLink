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
