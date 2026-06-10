# lanlink-core

A lightweight **Kotlin Multiplatform** library for LAN (local-network) device-to-device communication. It handles peer discovery, connection, authentication, and a typed message channel — so apps can build peer-to-peer features over Wi‑Fi without internet, accounts, or a backend.

`lanlink-core` ships an **Android** target today; the transport sits behind a `LanNetworkFactory` seam so additional platforms are added by supplying a new factory, not by rewriting the protocol.

> Looking for the full project, the reference chat app, and architecture overview? See the [repository README](../README.md).

## Features

- **Device discovery** — UDP broadcast + Android NSD (mDNS)
- **TCP transport** — Ktor-backed sockets with heartbeat and timeout handling
- **PIN pairing** — connect two devices that share a 6‑digit code
- **Pluggable authentication** — `AuthProvider` interface; built-in shared-secret provider with brute-force lockout
- **Typed message pipe** — each frame carries a `type` tag + raw bytes, so one connection multiplexes many message kinds
- **Coroutines & Flow** — `StateFlow`/`SharedFlow` for state, messages, and events

## Installation

```groovy
// settings.gradle
include(":lanlink-core")

// app/build.gradle
dependencies {
    implementation project(':lanlink-core')
}
```

### Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Quick start

### 1. Create the service

`PinConnectionService` is the entry point. On Android, wire it with `AndroidLanNetworkFactory`, which supplies the Ktor TCP transport and UDP discovery:

```kotlin
val service: PinConnectionService = PinConnectionServiceImpl(AndroidLanNetworkFactory())
```

### 2. Host or join with a PIN

Both devices use the **same 6‑digit PIN**. One hosts, the other joins.

```kotlin
// Host: start the server, then open a PIN pairing window
service.startServer()
service.startPairing(pin = "123456")

// Join (discovers, connects, and PIN-pairs with the host)
service.pairWithServer(pin = "123456")
```

### 3. Observe state, messages, and events

```kotlin
service.connectionState.collect { state ->
    when (state) {
        is PinConnectionState.Discovering -> { /* searching for peer */ }
        is PinConnectionState.Connected   -> { /* state.peerName is connected */ }
        is PinConnectionState.Error        -> { /* state.reason */ }
        else -> Unit
    }
}

service.messageFlow.collect { frame: TypedMessage ->
    if (frame.type == TYPE_CHAT) {
        val text = frame.payload.decodeToString()
    }
}

service.eventFlow.collect { event ->
    when (event) {
        is PinConnectionEvent.PeerConnected -> { /* event.peerName */ }
        is PinConnectionEvent.PeerDisconnected -> Unit
        is PinConnectionEvent.AuthFailed -> { /* event.reason */ }
    }
}
```

### 4. Send messages and clean up

```kotlin
const val TYPE_CHAT = 0

// `type` travels on the wire so the receiver knows how to parse the bytes.
service.send(type = TYPE_CHAT, data = "Hello!".encodeToByteArray())

// When done
service.disconnect()
```

## API overview

### `PinConnectionService`

| Member | Description |
|---|---|
| `connectionState: StateFlow<PinConnectionState>` | Idle → Discovering → Connecting → Connected / Error |
| `messageFlow: SharedFlow<TypedMessage>` | Inbound frames (`type` tag + raw `payload` bytes) |
| `eventFlow: SharedFlow<PinConnectionEvent>` | Peer connected/disconnected, auth failed |
| `pairingActive: StateFlow<Boolean>` | Whether the server PIN pairing window is open |
| `startServer()` | Host: open the TCP server + advertise; accept token reconnects |
| `startPairing(pin: String)` | Host: open the PIN pairing window (after `startServer()`) |
| `stopPairing()` | Host: close the PIN pairing window |
| `pairWithServer(pin: String)` | Join: first-time PIN pairing |
| `reconnectLastServer()` | Join: reconnect to the last paired server, no PIN |
| `send(type: Int, data: ByteArray)` | Send a typed frame to the peer |
| `disconnect()` | Tear down the session |

`PinConnectionServiceImpl` also accepts a `discoveryTimeoutMs` (default `30_000`).

### Connection state & events

```kotlin
sealed class PinConnectionState {
    object Idle : PinConnectionState()
    data class Discovering(val pin: String) : PinConnectionState()
    data class Connecting(val pin: String, val isServer: Boolean) : PinConnectionState()
    data class Connected(val pin: String, val isServer: Boolean, val peerName: String) : PinConnectionState()
    data class Error(val pin: String?, val reason: String) : PinConnectionState()
}

sealed class PinConnectionEvent {
    data class PeerConnected(val peerName: String) : PinConnectionEvent()
    object PeerDisconnected : PinConnectionEvent()
    data class AuthFailed(val reason: String) : PinConnectionEvent()
}
```

### The platform seam

`commonMain` orchestration depends only on interfaces; the platform provides the transport:

```kotlin
interface LanNetworkFactory {
    fun createServer(
        pairingRegistry: PairingRegistry,
        serverDeviceId: String = "",
        serverName: String = "",
    ): LanServer
    fun createClient(): LanClient
    fun createAdvertiser(servicePort: Int): DiscoveryAdvertiser
    fun createScanner(): DiscoveryScanner
}
```

| Component | Description |
|---|---|
| `TcpSocketServer` | Ktor TCP server, multi-client, heartbeat |
| `TcpSocketClient` | Ktor TCP client |
| `UdpDiscoveryServer` / `UdpDiscoveryClient` | UDP broadcast advertise / scan |
| `NsdAdvertiser` / `NsdDiscoverer` | Android NSD (mDNS) advertise / discover |
| `AndroidLanNetworkFactory` | Android wiring of all of the above |

## Authentication

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

### Built-in providers

| Provider | Description |
|---|---|
| `InMemoryAuthProvider` | 6‑digit shared-secret PIN; 5 failed attempts → 30s lockout |
| `NoOpAuthProvider` | Accepts every connection (tests only) |

```kotlin
val auth = InMemoryAuthProvider("123456") // explicit PIN
val auth = InMemoryAuthProvider()         // random secure 6-digit PIN
```

To use a custom provider, supply it through your own `LanNetworkFactory` (mirroring `AndroidLanNetworkFactory`).

## Configuration

```kotlin
TcpSocketServer(
    port = 0,                     // 0 = auto-select
    authProvider = authProvider,
    connectionTimeoutMs = 30_000, // heartbeat timeout
    heartbeatIntervalMs = 15_000
)

TcpSocketClient(
    connectionTimeoutMs = 10_000, // TCP connect timeout
    readTimeoutMs = 30_000,       // socket read timeout
    heartbeatIntervalMs = 15_000
)
```

## Wire format

Frames are **length-delimited protobuf** messages (`kotlinx-serialization-protobuf`). The transport carries a `TypedMessage(type: Int, payload: ByteArray)` — the core never inspects the payload, so your app owns the encoding for each `type`.

## Testing

```bash
# Unit tests run on the JVM, no device required
./gradlew :lanlink-core:testAndroidHostTest
```

## Security

> ⚠️ The default PIN handshake is **not encrypted** — credentials and messages travel in clear text over the LAN.

Built for trusted local networks. For untrusted environments, add a TLS/encrypted transport and consider certificate-based authentication; the `AuthProvider` seam is the intended extension point.

## License

Apache License 2.0 — see [LICENSE](../LICENSE).
