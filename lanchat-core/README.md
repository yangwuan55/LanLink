# LanComm

A lightweight Android library for LAN (Local Area Network) device-to-device communication.

**LanComm** enables Android apps to quickly build peer-to-peer communication over WiFi without requiring internet connectivity.

## Features

- **Device Discovery**: UDP broadcast + Android NSD (mDNS) support
- **TCP Connections**: Persistent connections with auto-reconnect
- **Pluggable Authentication**: Custom auth via `AuthProvider` interface
- **Flexible Payloads**: ByteArray messages - use any serialization (Protobuf, JSON, binary)
- **Kotlin Coroutines & Flow**: Modern async API

## Installation

```groovy
dependencies {
    implementation 'com.ymr.lancomm:core:1.0.0'
}
```

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
// Server mode
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

## API Overview

### Core Components

| Component | Description |
|-----------|-------------|
| `LanRepository` | Main entry point for all LAN communication |
| `TcpSocketServer` | TCP server for accepting connections |
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

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

## License

Apache 2.0