package com.ymr.lanlink.core.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Human-readable device name (e.g. the Android device model). */
expect fun deviceName(): String

/** Current wall-clock time in milliseconds since the Unix epoch. */
expect fun nowMillis(): Long

/** Dispatcher used for blocking I/O work. */
expect val ioDispatcher: CoroutineDispatcher

/** Cryptographically-strong random bytes of the given [size]. */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Returns the first non-loopback IPv4 address of this device,
 * or "127.0.0.1" if none can be determined.
 */
expect fun localIpv4Address(): String
