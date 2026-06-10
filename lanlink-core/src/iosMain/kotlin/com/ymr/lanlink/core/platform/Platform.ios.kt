package com.ymr.lanlink.core.platform

import kotlinx.coroutines.CoroutineDispatcher

// iOS is declared as a published target but intentionally NOT implemented yet.
// Every actual throws so a consumer's iOS code compiles and links, but fails
// fast at runtime instead of behaving incorrectly. Replace these with real
// platform implementations (UIDevice, SecRandomCopyBytes, getifaddrs, …) when
// iOS support is built out.
private const val NOT_IMPLEMENTED = "lanlink-core: iOS target is not implemented yet"

actual fun deviceName(): String = throw NotImplementedError(NOT_IMPLEMENTED)

actual fun nowMillis(): Long = throw NotImplementedError(NOT_IMPLEMENTED)

actual val ioDispatcher: CoroutineDispatcher
    get() = throw NotImplementedError(NOT_IMPLEMENTED)

actual fun secureRandomBytes(size: Int): ByteArray = throw NotImplementedError(NOT_IMPLEMENTED)

actual fun localIpv4Address(): String = throw NotImplementedError(NOT_IMPLEMENTED)
