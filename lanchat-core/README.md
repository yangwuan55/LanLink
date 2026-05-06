# LanComm

A lightweight Android library for LAN (Local Area Network) device-to-device communication.

**LanComm** enables Android apps to quickly build peer-to-peer communication over WiFi without requiring internet connectivity.

## Features

- **Device Discovery**: UDP broadcast + Android NSD (mDNS) support
- **TCP Connections**: Persistent connections with auto-reconnect
- **Pluggable Authentication**: Custom auth via `AuthProvider` interface
- **Flexible Payloads**: ByteArray messages - use any serialization (Protobuf, JSON, binary)
- **Kotlin Coroutines & Flow**: Modern async API
- **Heartbeat & Timeout**: Built-in connection health monitoring
- **Multi-client Support**: Server broadcasts to all connected clients
- **Brute-force Protection**: Lockout after failed authentication attempts

## Installation

### Gradle (Kotlin DSL)

```groovy
dependencies {
    implementation(project(":lanchat-core"))
}
```

### Requirements

```groovy
// settings.gradle.kts
include(":lanchat-core")
```

### Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

> Note: `ACCESS_FINE_LOCATION` is required for WiFi device discovery on Android 10+.

## Quick Start

### 1. Define Your Auth Provider

```kotlin
class MyAuthProvider : AuthProvider {
    override suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult {
        // Verify credentials and return result
        return AuthResult.Success(peerName)
    }

    override fun getCredentials(): ByteArray? {
        // Return your credentials bytes
        return myCredentials
    }
}
```

For shared secret authentication, use the built-in `InMemoryAuthProvider`:

```kotlin
// 6-digit PIN (required format)
val authProvider = InMemoryAuthProvider("123456")

// Or generate random secure PIN
val authProvider = InMemoryAuthProvider()  // Random 100000-999999
```

### 2. Set Up Discovery

```kotlin
val authProvider = MyAuthProvider()
val repository = LanRepository(context, authProvider)

// Discover peers
repository.startDiscovery()
repository.discoveredPeers.collect { peers ->
    println("Found ${peers.size} peers")
}
```

### 3. Connect and Send Messages

```kotlin
// Server mode (broadcasts to all clients)
repository.startServer()

// Client mode
repository.connectToPeer(peer)

repository.messages.collect { message ->
    val payload = message.payload.toStringUtf8()
    println("Received: $payload")
}

// Send message
repository.sendMessage("Hello!")
```

### 4. Cleanup

```kotlin
// When done, release resources
repository.close()
```

## API Overview

### Core Components

| Component | Description |
|-----------|-------------|
| `LanRepository` | Main entry point for all LAN communication |
| `TcpSocketServer` | TCP server for accepting connections (multi-client) |
| `TcpSocketClient` | TCP client for connecting to peers |
| `UdpDiscoveryServer` | UDP broadcast server (advertises presence) |
| `UdpDiscoveryClient` | UDP broadcast client (discovers peers) |
| `NsdAdvertiser` | Android NSD service registration |
| `NsdDiscoverer` | Android NSD service discovery |

### Auth Interface

```kotlin
interface AuthProvider {
    suspend fun authenticate(peerName: String, credentials: ByteArray?): AuthResult
    fun getCredentials(): ByteArray?
}

sealed class AuthResult {
    data class Success(val peerName: String) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}
```

### Built-in Auth Providers

| Provider | Description |
|----------|-------------|
| `NoOpAuthProvider` | Accepts all connections (testing only) |
| `InMemoryAuthProvider` | 6-digit PIN with brute-force protection |

### Flow APIs

```kotlin
// Connection state
val connectionState: StateFlow<ConnectionState>

// Discovered peers (UDP discovery)
val discoveredPeers: StateFlow<List<PeerInfo>>

// Incoming messages
val messages: SharedFlow<ByteArray>
```

### Connection State

```kotlin
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Discovering : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val peerName: String, val isServer: Boolean) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
```

## Message Format

Messages are sent as raw `ByteArray`. The library does not enforce any serialization format.

```kotlin
// Sending
repository.sendMessage("Hello".toByteArray(UTF_8))

// Receiving
repository.messages.collect { bytes ->
    val message = String(bytes, UTF_8)
    // process message
}
```

For Protobuf, use `com.google.protobuf` with custom_data fields in AuthRequest/AuthResponse.

## Error Handling

All network operations can throw exceptions. Wrap in try-catch:

```kotlin
try {
    repository.sendMessage("Hello")
} catch (e: Exception) {
    println("Send failed: ${e.message}")
}
```

### Common Errors

| Error | Cause |
|-------|-------|
| `Connection failed: ...` | Cannot reach peer |
| `Unknown host: ...` | Invalid IP/hostname |
| `Account locked...` | Too many failed auth attempts |

## Configuration

### TcpSocketServer

```kotlin
TcpSocketServer(
    port = 0,                    // Auto-select port
    authProvider = authProvider,
    connectionTimeoutMs = 30_000, // Heartbeat timeout
    heartbeatIntervalMs = 15_000  // Heartbeat interval
)
```

### TcpSocketClient

```kotlin
TcpSocketClient(
    connectionTimeoutMs = 10_000,  // TCP connect timeout
    readTimeoutMs = 30_000,       // Socket read timeout
    heartbeatIntervalMs = 15_000  // Heartbeat interval
)
```

### InMemoryAuthProvider

```kotlin
InMemoryAuthProvider(
    expectedPin = "123456"        // 6-digit PIN
)
// Brute-force protection: 5 attempts, then 30s lockout
```

## Architecture

```
app/
├── presentation/
│   ├── MainActivity.kt       # UI
│   └── LanViewModel.kt       # Presentation logic
├── data/
│   └── repository/
│       └── LanRepository.kt   # Facade for core library
└── service/
    └── LanForegroundService.kt

lanchat-core/
├── data/
│   ├── socket/               # TCP client/server
│   ├── discovery/            # UDP/NSD discovery
│   └── auth/                  # Auth providers
└── domain/
    ├── model/                # Domain models
    └── auth/                  # Auth interfaces
```

## Testing

```bash
# Unit tests (lanchat-core)
./gradlew :lanchat-core:test

# Unit tests (app)
./gradlew :app:test

# Android instrumented tests
./gradlew :app:connectedAndroidTest

# Robot Framework E2E tests
./tests/run_test.sh
```

## Security Considerations

> **WARNING**: Default PIN authentication sends credentials in plain text.

For production use:
- Implement TLS/SSL encryption
- Use certificate-based authentication
- Consider using a proper authentication library

## License

Apache 2.0