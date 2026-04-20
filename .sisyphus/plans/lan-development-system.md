# LAN Development System - Work Plan

## TL;DR

> **Quick Summary**: Build an Android LAN system with server and client components for bidirectional TCP socket communication using Protocol Buffers, with Android NSD for automatic service discovery.
>
> **Deliverables**:
> - Android library/module with clean architecture
> - Server component (advertises via NSD, accepts TCP connections)
> - Client component (discovers via NSD, connects to server)
> - Bidirectional data channel using Protocol Buffers
> - Authentication abstraction interface
> - Foreground Service for server persistence
>
> **Estimated Effort**: Medium-Large
> **Parallel Execution**: YES - 3 waves
> **Critical Path**: Wave 1 (Project + Proto) → Wave 2 (Core) → Wave 3 (Integration)

---

## Context

### Original Request
Build a LAN development system with Android server and client components for bidirectional data communication.

### Interview Summary
**Key Discussions**:
- **Core purpose**: Bidirectional socket communication
- **Data format**: Protocol Buffers
- **Authentication**: Abstract interface (user implements own)
- **Connection**: 1-to-1 (one server, one client)
- **Language**: Kotlin
- **Min SDK**: Android API 24 (Android 7.0)

**Research Findings**:
- Android NSD (NsdManager) is the standard for LAN service discovery
- TCP sockets provide reliable bidirectional communication
- Protocol Buffers require protobuf-javalite (NOT protobuf-java)
- Foreground Service needed for server persistence
- MulticastLock required before NSD discovery on many devices

### Metis Review
**Identified Gaps (addressed)**:
- Confirmed need for MulticastLock before discovery
- Confirmed always resolve service before accessing host/port
- Confirmed use dynamic port (port=0)
- Confirmed ProGuard rule for protobuf
- Confirmed no encryption in core (Auth interface handles it)

**Guardrails Applied**:
- MUST NOT use HTTP/WebSocket - raw TCP only
- MUST NOT hardcode ports
- MUST NOT block UI thread
- MUST acquire MulticastLock before discovery
- MUST resolve service before using host/port

---

## Work Objectives

### Core Objective
Create an Android library enabling bidirectional data communication between two devices on the same LAN, using Android NSD for automatic discovery and TCP sockets for reliable data transfer with Protocol Buffers serialization.

### Concrete Deliverables
- `app/build.gradle` with protobuf plugin configured
- `proto/lan_service.proto` defining message types
- `domain/model/` - ConnectionState, LanMessage entities
- `domain/auth/` - AuthProvider interface
- `data/nsd/` - NsdAdvertiser, NsdDiscoverer
- `data/socket/` - SocketManager (TCP client/server)
- `data/repository/` - LanRepository implementation
- `service/` - LanForegroundService
- `presentation/` - LanViewModel

### Definition of Done
- [ ] Server starts and advertises via NSD within 3 seconds
- [ ] Client discovers and resolves server via NSD within 5 seconds
- [ ] TCP socket connection established after discovery
- [ ] Bidirectional data send/receive works
- [ ] Auth interface callback invoked on connection
- [ ] App survives backgrounding when server running
- [ ] Graceful error handling on network issues

### Must Have
- Android NSD service registration and discovery
- TCP socket connection management
- Protocol Buffers message serialization
- Auth interface abstraction (no-op implementation)
- Foreground Service for server persistence
- Clean Architecture with MVVM presentation

### Must NOT Have (Guardrails)
- No HTTP/WebSocket - raw TCP only
- No encryption in core - Auth interface handles it
- No hardcoded ports - use port=0
- No blocking UI thread - Coroutines only
- No plaintext credential storage
- No direct host/port access before resolveService()

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: NO (new project)
- **Automated tests**: Tests-after (unit tests after implementation)
- **Framework**: JUnit + Android Testing (instrumented tests)
- **Agent-Executed QA**: YES - Every task has QA scenarios

### QA Policy
Every task includes agent-executed QA scenarios. Evidence saved to `.sisyphus/evidence/`.

---

## Execution Strategy

### Wave Structure

```
Wave 1 (Foundation - max parallel):
├── T1: Project setup + Gradle config
├── T2: Proto schema definition
├── T3: Auth interface abstraction
├── T4: Connection state entities
└── T5: Base socket abstractions

Wave 2 (Core - max parallel):
├── T6: NSD Advertiser (Server side)
├── T7: NSD Discoverer (Client side)
├── T8: TCP Socket Server
├── T9: TCP Socket Client
└── T10: Protocol Buffers channel

Wave 3 (Integration):
├── T11: LanRepository (combines NSD + Socket)
├── T12: Foreground Service
├── T13: LanViewModel
└── T14: MainActivity UI

Wave FINAL (Verification):
├── F1: Plan compliance audit
├── F2: Code quality review
├── F3: Real QA execution
└── F4: Scope fidelity check
```

### Dependency Matrix

| Task | Blocks | Blocked By |
|------|--------|------------|
| T1 | T2, T3, T4, T5 | - |
| T2 | T10 | T1 |
| T3 | T10, T11 | T1 |
| T4 | T10, T11 | T1 |
| T5 | T6, T7, T8, T9 | T1 |
| T6 | T11 | T5 |
| T7 | T11 | T5 |
| T8 | T10 | T5 |
| T9 | T10 | T5 |
| T10 | T11 | T2, T3, T4, T8, T9 |
| T11 | T12 | T3, T4, T6, T7, T10 |
| T12 | T13 | T11 |
| T13 | T14 | T12 |
| T14 | F1-F4 | T13 |

---

## TODOs

- [x] 1. **Project Setup + Gradle Configuration**

  **What to do**:
  - Create `settings.gradle` with project name
  - Create `build.gradle` (project level) with plugins
  - Create `app/build.gradle` with:
    - `com.android.application` plugin
    - `org.jetbrains.kotlin.android` plugin
    - `com.google.protobuf` plugin (version 0.9.4)
    - `protobuf-javalite` + `protobuf-kotlin-lite` dependencies (version 3.25.x)
    - minSdk 24, targetSdk 34
  - Create `gradle.properties` with AndroidX enabled
  - Create `local.properties` with SDK path
  - Create basic Android manifest with required permissions
  - Create minimal MainActivity as entry point
  - Generate Gradle wrapper

  **Must NOT do**:
  - Don't include protobuf-java (only javalite)
  - Don't hardcode SDK versions

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Standard project scaffolding, well-documented Gradle patterns
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `vercel-react-best-practices`: Not relevant to Android

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with T2, T3, T4, T5)
  - **Blocks**: T2, T3, T4, T5
  - **Blocked By**: None

  **References**:
  - `https://developer.android.com/studio/build/gradle-tips` - Gradle configuration
  - `https://github.com/google/protobuf-gradle-plugin` - Protobuf plugin setup
  - `https://developer.android.com/studio/build/manage-dependencies` - Dependency management

  **Acceptance Criteria**:
  - [ ] Project syncs without errors
  - [ ] `./gradlew assembleDebug` succeeds
  - [ ] Protobuf plugin generates Lite stubs

  **QA Scenarios**:

  ```
  Scenario: Gradle build succeeds
    Tool: Bash
    Preconditions: Clean workspace
    Steps:
      1. cd /Users/ymr/github/localnetwork
      2. ./gradlew assembleDebug --no-daemon
    Expected Result: BUILD SUCCESSFUL in <120s
    Failure Indicators: BUILD FAILED, sync errors, missing dependencies
    Evidence: .sisyphus/evidence/task-1-build.log

  Scenario: Protobuf plugin configured correctly
    Tool: Bash
    Preconditions: Project synced
    Steps:
      1. ./gradlew generateDebugProto --no-daemon
      2. ls app/build/generated/source/proto/debug/java/
    Expected Result: Generated .java files exist from proto schema
    Failure Indicators: No generated files, Protobuf plugin errors
    Evidence: .sisyphus/evidence/task-1-proto-generate.log
  ```

  **Commit**: YES
  - Message: `init: project setup with Gradle and protobuf configuration`
  - Files: `settings.gradle`, `build.gradle`, `app/build.gradle`, `gradle.properties`, `local.properties`, `gradlew*`, `AndroidManifest.xml`
  - Pre-commit: `./gradlew assembleDebug`

---

- [x] 2. **Protocol Buffers Schema Definition**

  **What to do**:
  - Create `app/src/main/proto/lan_service.proto`
  - Define message types:
    ```protobuf
    syntax = "proto3";
    option java_package = "com.example.lanchat.proto";
    option java_multiple_files = true;

    message LanMessage {
      string id = 1;
      int64 timestamp = 2;
      string payload = 3;
    }

    message AuthRequest {
      string device_name = 1;
      bytes credentials = 2;
    }

    message AuthResponse {
      bool success = 1;
      string message = 2;
    }

    message ConnectionStatus {
      bool connected = 1;
      string peer_name = 2;
    }
    ```
  - Run protobuf code generation
  - Verify generated Kotlin/Java files

  **Must NOT do**:
  - Don't use proto2 syntax
  - Don't use java_outer_classname (not needed with java_multiple_files)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Proto file creation is straightforward
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - N/A - straightforward file creation

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T1)
  - **Parallel Group**: Wave 1 (with T1, T3, T4, T5)
  - **Blocks**: T10
  - **Blocked By**: T1

  **References**:
  - `https://developers.google.com/protocol-buffers/docs/proto3` - Proto3 syntax
  - `https://github.com/google/protobuf-gradle-plugin` - Code generation
  - `https://github.com/protocolbuffers/protobuf/blob/main/java/kotlin/README.md` - Kotlin Lite

  **Acceptance Criteria**:
  - [ ] Proto file syntactically valid
  - [ ] Generated code exists in `app/build/generated/source/proto/`
  - [ ] Generated classes implement `com.google.protobuf.GeneratedMessageLite`

  **QA Scenarios**:

  ```
  Scenario: Proto schema compiles successfully
    Tool: Bash
    Preconditions: T1 complete
    Steps:
      1. ./gradlew generateDebugProto --no-daemon
      2. ls app/build/generated/source/proto/debug/java/com/example/lanchat/proto/
    Expected Result: Files LanMessage.java, AuthRequest.java, AuthResponse.java, ConnectionStatus.java exist
    Failure Indicators: Compilation errors, missing generated files
    Evidence: .sisyphus/evidence/task-2-proto-output.txt

  Scenario: Generated message types are correct
    Tool: Bash
    Preconditions: Proto generated
    Steps:
      1. grep -l "LanMessage" app/build/generated/source/proto/debug/java/com/example/lanchat/proto/*.java
    Expected Result: LanMessage.java exists
    Evidence: .sisyphus/evidence/task-2-lanmessage.txt
  ```

  **Commit**: YES
  - Message: `feat: add Protocol Buffers schema for LAN messages`
  - Files: `app/src/main/proto/lan_service.proto`
  - Pre-commit: `./gradlew generateDebugProto`

---

- [x] 3. **Auth Interface Abstraction**

  **What to do**:
  - Create `domain/auth/AuthProvider.kt` interface:
    ```kotlin
    interface AuthProvider {
      suspend fun authenticate(peerName: String, credentials: ByteArray): AuthResult
      fun getCredentials(): ByteArray?
    }

    sealed class AuthResult {
      data class Success(val peerName: String) : AuthResult()
      data class Failure(val message: String) : AuthResult()
    }
    ```
  - Create `data/auth/NoOpAuthProvider.kt` - default no-op implementation
  - Create `data/auth/InMemoryAuthProvider.kt` - simple PIN/password implementation for testing

  **Must NOT do**:
  - Don't implement encryption in core
  - Don't store credentials in plaintext in production impl
  - Don't make this blocking (suspend function required)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple interface definition
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - N/A - straightforward

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with T1, T2, T4, T5)
  - **Blocks**: T10, T11
  - **Blocked By**: T1

  **References**:
  - `app/src/main/proto/lan_service.proto` - AuthRequest, AuthResponse types

  **Acceptance Criteria**:
  - [ ] AuthProvider interface defined with suspend function
  - [ ] AuthResult sealed class with Success/Failure
  - [ ] NoOpAuthProvider returns Success always
  - [ ] Code compiles without errors

  **QA Scenarios**:

  ```
  Scenario: AuthProvider interface compiles
    Tool: Bash
    Preconditions: T1, T2 complete
    Steps:
      1. ./gradlew compileDebugKotlin --no-daemon
    Expected Result: BUILD SUCCESSFUL
    Failure Indicators: Type errors, missing imports
    Evidence: .sisyphus/evidence/task-3-auth-compile.log

  Scenario: NoOpAuthProvider returns Success
    Tool: Bash
    Preconditions: Build succeeds
    Steps:
      1. cd /Users/ymr/github/localnetwork
      2. echo "val provider = NoOpAuthProvider(); println(provider.authenticate('test', null) is AuthResult.Success)" | kotlinc -script -
    Expected Result: Prints "true" (simulated - actual test via unit test)
    Evidence: .sisyphus/evidence/task-3-noop-test.txt
  ```

  **Commit**: YES
  - Message: `feat: add AuthProvider interface abstraction`
  - Files: `domain/auth/AuthProvider.kt`, `data/auth/NoOpAuthProvider.kt`, `data/auth/InMemoryAuthProvider.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 4. **Connection State Entities**

  **What to do**:
  - Create `domain/model/ConnectionState.kt`:
    ```kotlin
    sealed class ConnectionState {
      object Idle : ConnectionState()
      object Discovering : ConnectionState()
      object Connecting : ConnectionState()
      data class Connected(val peerName: String, val isServer: Boolean) : ConnectionState()
      data class Error(val message: String) : ConnectionState()
    }
    ```
  - Create `domain/model/LanMessage.kt` - wrapper for protobuf messages
  - Create `domain/model/PeerInfo.kt` - discovered peer information

  **Must NOT do**:
  - Don't include actual socket references in domain model
  - Don't make this Android-dependent (no Context, etc.)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple data class definitions
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with T1, T2, T3, T5)
  - **Blocks**: T10, T11
  - **Blocked By**: T1

  **References**:
  - Standard Kotlin sealed class patterns

  **Acceptance Criteria**:
  - [ ] ConnectionState sealed class with all states
  - [ ] LanMessage data class with id, timestamp, payload
  - [ ] PeerInfo with name, host, port
  - [ ] All serializable/mappable to protobuf types

  **QA Scenarios**:

  ```
  Scenario: Domain models compile
    Tool: Bash
    Preconditions: T1 complete
    Steps:
      1. ./gradlew compileDebugKotlin --no-daemon
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-4-models-compile.log
  ```

  **Commit**: YES
  - Message: `feat: define ConnectionState and domain models`
  - Files: `domain/model/ConnectionState.kt`, `domain/model/LanMessage.kt`, `domain/model/PeerInfo.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 5. **Base Socket Abstractions**

  **What to do**:
  - Create `data/socket/SocketConfig.kt` - configuration data class
  - Create `data/socket/SocketMessage.kt` - wrapper for send/receive
  - Create `data/socket/SocketCallback.kt` - listener interface:
    ```kotlin
    interface SocketCallback {
      fun onConnected()
      fun onDisconnected()
      fun onMessageReceived(message: ByteArray)
      fun onError(error: Throwable)
    }
    ```
  - Create `data/socket/SocketChannel.kt` - abstraction for read/write

  **Must NOT do**:
  - Don't implement actual socket logic here (defer to T8, T9)
  - Don't block - all operations must be suspend or callback-based

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Interface/abstraction definition
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with T1, T2, T3, T4)
  - **Blocks**: T6, T7, T8, T9
  - **Blocked By**: T1

  **References**:
  - Kotlin Coroutines patterns for async I/O

  **Acceptance Criteria**:
  - [ ] SocketCallback interface defined
  - [ ] SocketConfig data class with host, port
  - [ ] SocketChannel abstraction
  - [ ] Code compiles

  **QA Scenarios**:

  ```
  Scenario: Socket abstractions compile
    Tool: Bash
    Preconditions: T1 complete
    Steps:
      1. ./gradlew compileDebugKotlin --no-daemon
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-5-abstractions-compile.log
  ```

  **Commit**: YES
  - Message: `feat: define socket abstractions and callbacks`
  - Files: `data/socket/SocketConfig.kt`, `data/socket/SocketMessage.kt`, `data/socket/SocketCallback.kt`, `data/socket/SocketChannel.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 6. **NSD Advertiser (Server Side)**

  **What to do**:
  - Create `data/nsd/NsdAdvertiser.kt`:
    ```kotlin
    class NsdAdvertiser(private val context: Context) {
      private var nsdManager: NsdManager? = null
      private var registrationListener: NsdManager.RegistrationListener? = null
      private var registeredServiceInfo: NsdServiceInfo? = null

      suspend fun registerService(serviceName: String, port: Int)
      fun unregisterService()
      val registrationState: StateFlow<NsdRegistrationState>
    }

    sealed class NsdRegistrationState {
      object Idle : NsdRegistrationState()
      object Registering : NsdRegistrationState()
      data class Registered(val serviceName: String, val port: Int) : NsdRegistrationState()
      data class Error(val message: String) : NsdRegistrationState()
    }
    ```
  - Acquire MulticastLock before registration
  - Use dynamic port (port=0) to avoid conflicts
  - Handle onServiceRegistered callback properly
  - Add ProGuard rule for protobuf if not already present

  **Must NOT do**:
  - Don't access host/port in onServiceFound callback
  - Don't skip MulticastLock
  - Don't hardcode service type (use `_lanchat._tcp.`)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Android NSD API is complex with multiple callbacks
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T5)
  - **Parallel Group**: Wave 2 (with T7, T8, T9, T10)
  - **Blocks**: T11
  - **Blocked By**: T5

  **References**:
  - `https://developer.android.com/develop/connectivity/wifi/use-nsd` - Official NSD docs
  - `https://github.com/aroio/android-nsd-flow` - Flow-based NSD wrapper
  - `https://github.com/kiwix/kiwix-android/blob/main/app/src/main/java/org/kiwix/kiwixmobile/localFileTransfer/WifiDirectManager.kt` - NSD handling patterns

  **Acceptance Criteria**:
  - [ ] Service registered within 3 seconds
  - [ ] RegistrationListener callbacks fire correctly
  - [ ] MulticastLock acquired before registration
  - [ ] NsdRegistrationState emits correct states
  - [ ] UnregisterService cleans up properly

  **QA Scenarios**:

  ```
  Scenario: NSD registration succeeds
    Tool: Bash
    Preconditions: T1, T5, T6 complete, app installed on device
    Steps:
      1. Start server on test device
      2. Run: adb shell dumpsys connectivity | grep _lanchat
    Expected Result: Service appears in connectivity dump
    Evidence: .sisyphus/evidence/task-6-nsd-register.txt

  Scenario: MulticastLock acquired before discovery
    Tool: grep
    Preconditions: T6 source exists
    Steps:
      1. grep -n "MulticastLock" app/src/main/java/com/example/lanchat/data/nsd/*.kt
    Expected Result: Found in NsdAdvertiser before registerService call
    Evidence: .sisyphus/evidence/task-6-multicast-check.txt
  ```

  **Commit**: YES
  - Message: `feat: implement NSD advertiser for service registration`
  - Files: `data/nsd/NsdAdvertiser.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 7. **NSD Discoverer (Client Side)**

  **What to do**:
  - Create `data/nsd/NsdDiscoverer.kt`:
    ```kotlin
    class NsdDiscoverer(private val context: Context) {
      private var nsdManager: NsdManager? = null
      private var discoveryListener: NsdManager.DiscoveryListener? = null
      private var resolveListener: NsdManager.ResolveListener? = null

      suspend fun discoverServices(serviceType: String = "_lanchat._tcp.")
      fun stopDiscovery()
      val discoveredPeers: StateFlow<List<PeerInfo>>
      val discoveryState: StateFlow<NsdDiscoveryState>
    }

    sealed class NsdDiscoveryState {
      object Idle : NsdDiscoveryState()
      object Discovering : NsdDiscoveryState()
      object Stopped : NsdDiscoveryState()
      data class Error(val message: String) : NsdDiscoveryState()
    }
    ```
  - Acquire MulticastLock before discovery
  - CRITICAL: Always resolve service before accessing host/port
  - Implement timeout (30 seconds) for discovery
  - Filter out self (same device) from results

  **Must NOT do**:
  - Don't use serviceInfo.host or serviceInfo.port directly in onServiceFound
  - Don't skip resolveService() call
  - Don't forget to release MulticastLock

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: NSD discovery requires careful handling of resolveService
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T5)
  - **Parallel Group**: Wave 2 (with T6, T8, T9, T10)
  - **Blocks**: T11
  - **Blocked By**: T5

  **References**:
  - Same as T6 - Official NSD docs
  - `https://github.com/plainhub/plain-app/blob/main/app/src/main/java/com/ismartcoding/plain/web/NsdHelper.kt` - Complete NSD implementation

  **Acceptance Criteria**:
  - [ ] Service discovery starts within 1 second
  - [ ] onServiceFound fires for available services
  - [ ] resolveService correctly populates host/port
  - [ ] PeerInfo list updated with resolved services
  - [ ] Discovery stops cleanly

  **QA Scenarios**:

  ```
  Scenario: Service discovery finds advertised server
    Tool: Bash
    Preconditions: T6 running on server device, T7 complete
    Steps:
      1. Start client on second device
      2. Wait 5 seconds
      3. Check logcat: adb logcat -d | grep -i "nsd.*found\|discovered"
    Expected Result: Server service appears in discovery results
    Evidence: .sisyphus/evidence/task-7-discovery.log

  Scenario: Resolve service before using host/port
    Tool: grep
    Preconditions: T7 source exists
    Steps:
      1. grep -A5 "onServiceFound" app/src/main/java/com/example/lanchat/data/nsd/NsdDiscoverer.kt
    Expected Result: resolveService called before host/port access
    Evidence: .sisyphus/evidence/task-7-resolve-check.txt
  ```

  **Commit**: YES
  - Message: `feat: implement NSD discoverer for service discovery`
  - Files: `data/nsd/NsdDiscoverer.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 8. **TCP Socket Server**

  **What to do**:
  - Create `data/socket/TcpSocketServer.kt`:
    ```kotlin
    class TcpSocketServer(
      private val port: Int = 0,  // 0 = dynamic
      private val callback: SocketCallback
    ) {
      private var serverSocket: ServerSocket? = null
      private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

      suspend fun start(): Int  // returns actual port
      fun stop()
      suspend fun send(message: ByteArray)
      val isRunning: Boolean
    }
    ```
  - Use ServerSocket with port=0 for dynamic port allocation
  - Accept connections in coroutine
  - Handle client connections with proper cleanup
  - Thread-safe state management

  **Must NOT do**:
  - Don't block the caller on accept (use suspend)
  - Don't leak sockets on errors
  - Don't ignore SocketException

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Socket server requires careful async handling
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T5)
  - **Parallel Group**: Wave 2 (with T6, T7, T9, T10)
  - **Blocks**: T10
  - **Blocked By**: T5

  **References**:
  - Kotlin Coroutines for async I/O
  - Java ServerSocket patterns

  **Acceptance Criteria**:
  - [ ] Server starts and returns assigned port
  - [ ] Accepts incoming connections
  - [ ] Calls onConnected callback on successful accept
  - [ ] Calls onDisconnected callback on client disconnect
  - [ ] Graceful shutdown without exceptions

  **QA Scenarios**:

  ```
  Scenario: TCP server starts on dynamic port
    Tool: Bash
    Preconditions: T1, T5, T8 complete
    Steps:
      1. Start TcpSocketServer with port=0
      2. Get assigned port
      3. Connect via telnet to that port
    Expected Result: Connection accepted, onConnected fires
    Evidence: .sisyphus/evidence/task-8-server-start.log

  Scenario: Server accepts client connection
    Tool: interactive_bash
    Preconditions: Server running
    Steps:
      1. echo "test" | nc localhost <port>
      2. Check onConnected callback fired
    Expected Result: Callback received, connection established
    Evidence: .sisyphus/evidence/task-8-accept.log
  ```

  **Commit**: YES
  - Message: `feat: implement TCP socket server`
  - Files: `data/socket/TcpSocketServer.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 9. **TCP Socket Client**

  **What to do**:
  - Create `data/socket/TcpSocketClient.kt`:
    ```kotlin
    class TcpSocketClient(private val callback: SocketCallback) {
      private var socket: Socket? = null
      private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

      suspend fun connect(host: String, port: Int)
      fun disconnect()
      suspend fun send(message: ByteArray)
      val isConnected: Boolean
    }
    ```
  - Connect to host:port in coroutine
  - Handle connection failures gracefully
  - Automatic reconnection with exponential backoff (configurable)

  **Must NOT do**:
  - Don't block on connect
  - Don't skipSocketException handling
  - Don't leak socket resources

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Socket client with async patterns
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T5)
  - **Parallel Group**: Wave 2 (with T6, T7, T8, T10)
  - **Blocks**: T10
  - **Blocked By**: T5

  **References**:
  - Same patterns as TcpSocketServer

  **Acceptance Criteria**:
  - [ ] Connect succeeds to valid host:port
  - [ ] Connect fails gracefully to invalid host:port
  - [ ] onConnected fires on successful connect
  - [ ] onError fires on connection failure
  - [ ] Disconnect cleans up properly

  **QA Scenarios**:

  ```
  Scenario: Client connects to server
    Tool: interactive_bash
    Preconditions: TcpSocketServer running on port X, T9 complete
    Steps:
      1. Start TcpSocketClient
      2. Call connect("localhost", X)
      3. Check onConnected callback
    Expected Result: Connection established, callback fires
    Evidence: .sisyphus/evidence/task-9-connect.log

  Scenario: Client handles connection failure
    Tool: interactive_bash
    Preconditions: No server running
    Steps:
      1. Start TcpSocketClient
      2. Call connect("localhost", 9999)
      3. Check onError callback
    Expected Result: onError fires with appropriate exception
    Evidence: .sisyphus/evidence/task-9-error.log
  ```

  **Commit**: YES
  - Message: `feat: implement TCP socket client with reconnection`
  - Files: `data/socket/TcpSocketClient.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 10. **Protocol Buffers Channel**

  **What to do**:
  - Create `data/socket/ProtobufChannel.kt`:
    ```kotlin
    class ProtobufChannel(private val socketChannel: SocketChannel) {
      suspend fun send(message: LanMessage)
      suspend fun receive(): LanMessage
      fun sendAuthRequest(request: AuthRequest)
      fun sendAuthResponse(response: AuthResponse)
    }
    ```
  - Wrap raw ByteArray with protobuf serialization
  - Use length-prefixed message framing (4 bytes length + payload)
  - Handle message boundaries correctly
  - Handle incomplete reads

  **Must NOT do**:
  - Don't use java protobuf (must use protobuf-javalite)
  - Don't assume messages arrive in single packet
  - Don't skip length prefix (need framing for multiple messages)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Protocol buffer serialization with socket framing
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after T2, T3, T4, T8, T9)
  - **Parallel Group**: Wave 2 (with T6, T7, T8, T9)
  - **Blocks**: T11
  - **Blocked By**: T2, T3, T4, T8, T9

  **References**:
  - `https://developers.google.com/protocol-buffers/docs/reference/java-generated` - Message generation
  - Socket framing patterns (length-prefixed)

  **Acceptance Criteria**:
  - [ ] LanMessage serializes and deserializes correctly
  - [ ] Multiple messages can be sent/received in sequence
  - [ ] AuthRequest/Response work through channel
  - [ ] Handle messages larger than TCP buffer size

  **QA Scenarios**:

  ```
  Scenario: Protobuf serialization round-trip
    Tool: Bash
    Preconditions: T2, T10 complete
    Steps:
      1. Create LanMessage with known payload
      2. Serialize to ByteArray
      3. Deserialize back to LanMessage
      4. Compare payload field
    Expected Result: Original payload matches
    Evidence: .sisyphus/evidence/task-10-serialize.log

  Scenario: Channel sends and receives protobuf messages
    Tool: interactive_bash
    Preconditions: TcpSocketServer + TcpSocketClient + T10
    Steps:
      1. Connect client to server
      2. Server sends LanMessage
      3. Client receives and deserializes
    Expected Result: Message correctly transmitted
    Evidence: .sisyphus/evidence/task-10-channel.log
  ```

  **Commit**: YES
  - Message: `feat: implement Protocol Buffers channel with framing`
  - Files: `data/socket/ProtobufChannel.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 11. **LanRepository (NSD + Socket Integration)**

  **What to do**:
  - Create `data/repository/LanRepository.kt`:
    ```kotlin
    class LanRepository(
      private val context: Context,
      private val authProvider: AuthProvider
    ) {
      private val nsdAdvertiser = NsdAdvertiser(context)
      private val nsdDiscoverer = NsdDiscoverer(context)
      private val socketManager = SocketManager()
      private val protobufChannel: ProtobufChannel?

      val connectionState: StateFlow<ConnectionState>
      val discoveredPeers: StateFlow<List<PeerInfo>>
      val messages: StateFlow<LanMessage>

      // Server mode
      suspend fun startServer()
      fun stopServer()

      // Client mode
      suspend fun connectToPeer(peer: PeerInfo)
      fun disconnect()

      // Messaging
      suspend fun sendMessage(payload: String)
      suspend fun authenticate()
    }
    ```
  - Combine NSD discovery/registration with socket connection
  - Implement connection state machine
  - Handle NSD → Socket transition (get port from NSD, pass to socket)
  - Authenticate on connection using AuthProvider

  **Must NOT do**:
  - Don't expose raw socket to presentation layer
  - Don't block during discovery (use suspend)
  - Don't skip auth step

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Complex state management and integration
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T3, T4, T6, T7, T10)
  - **Parallel Group**: Wave 3
  - **Blocks**: T12
  - **Blocked By**: T3, T4, T6, T7, T10

  **References**:
  - Clean Architecture Repository pattern
  - StateFlow for reactive state

  **Acceptance Criteria**:
  - [ ] Server starts NSD + Socket in sequence
  - [ ] Client discovers → resolves → connects
  - [ ] AuthProvider.authenticate called on connection
  - [ ] ConnectionState transitions correctly
  - [ ] Messages flow bidirectionally

  **QA Scenarios**:

  ```
  Scenario: Full server start sequence
    Tool: Bash
    Preconditions: T6, T8, T10, T11 complete
    Steps:
      1. Call startServer()
      2. Verify NSD registered
      3. Verify socket listening
      4. Check port matches
    Expected Result: Server ready on NSD-advertised port
    Evidence: .sisyphus/evidence/task-11-server-start.log

  Scenario: Full client connection sequence
    Tool: Bash
    Preconditions: Server running, T7, T9, T10, T11 complete
    Steps:
      1. Start discovery
      2. Get discovered peer
      3. Connect to peer
      4. Verify connected state
    Expected Result: Connection established, auth attempted
    Evidence: .sisyphus/evidence/task-11-client-connect.log
  ```

  **Commit**: YES
  - Message: `feat: implement LanRepository combining NSD and socket`
  - Files: `data/repository/LanRepository.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 12. **Foreground Service**

  **What to do**:
  - Create `service/LanForegroundService.kt`:
    ```kotlin
    class LanForegroundService : Service() {
      private val binder = LocalBinder()
      private var repository: LanRepository? = null

      override fun onCreate()
      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
      override fun onBind(intent: Intent): IBinder
      override fun onDestroy()

      inner class LocalBinder : Binder() {
        fun getService(): LanForegroundService = this@LanForegroundService
      }
    }
    ```
  - Start with FOREGROUND_SERVICE_SPECIAL_USE notification
  - Show service name, connection status in notification
  - Bind to Activity for UI control
  - Survive app backgrounding
  - Proper cleanup in onDestroy

  **Must NOT do**:
  - Don't use deprecated foreground service types
  - Don't forget to stopSelf() on unbind
  - Don't leak service reference

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Android Service lifecycle complexity
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T11)
  - **Parallel Group**: Wave 3
  - **Blocks**: T13
  - **Blocked By**: T11

  **References**:
  - `https://developer.android.com/develop/background/components/services` - Service best practices
  - `https://developer.android.com/about/versions/14/changes/fgs-types` - API 34 foreground changes

  **Acceptance Criteria**:
  - [ ] Service starts as foreground with notification
  - [ ] Notification shows service name and status
  - [ ] Service survives app backgrounding
  - [ ] Service stops cleanly on destroy
  - [ ] Bind/unbind work correctly

  **QA Scenarios**:

  ```
  Scenario: Service starts as foreground
    Tool: Bash
    Preconditions: T12 complete, app installed
    Steps:
      1. Start service via startService()
      2. Check notification bar
      3. Run: adb shell dumpsys activity service <package>/.LanForegroundService
    Expected Result: Notification visible, service running
    Evidence: .sisyphus/evidence/task-12-service-start.log

  Scenario: Service notification is persistent
    Tool: Bash
    Preconditions: Service running
    Steps:
      1. adb shell "dumpsys notification --noredact"
    Expected Result: Notification with FOREGROUND_SERVICE type
    Evidence: .sisyphus/evidence/task-12-notification.txt
  ```

  **Commit**: YES
  - Message: `feat: implement Foreground Service for server persistence`
  - Files: `service/LanForegroundService.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 13. **LanViewModel**

  **What to do**:
  - Create `presentation/LanViewModel.kt`:
    ```kotlin
    class LanViewModel(
      private val repository: LanRepository
    ) : ViewModel() {

      val connectionState: StateFlow<ConnectionState>
      val discoveredPeers: StateFlow<List<PeerInfo>>
      val messages: StateFlow<List<LanMessage>>

      // Server actions
      fun startServer()
      fun stopServer()

      // Client actions
      fun startDiscovery()
      fun stopDiscovery()
      fun connectToPeer(peer: PeerInfo)
      fun disconnect()

      // Messaging
      fun sendMessage(payload: String)

      // Auth
      fun setAuthProvider(authProvider: AuthProvider)
    }
    ```
  - Expose Repository state via ViewModel
  - Handle UI actions and call Repository
  - Use SavedStateHandle for process death
  - Don't expose Repository directly to UI

  **Must NOT do**:
  - Don't do network I/O on main thread
  - Don't leak context (use applicationContext)
  - Don't hold Activity reference

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: MVVM architecture with StateFlow
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T12)
  - **Parallel Group**: Wave 3
  - **Blocks**: T14
  - **Blocked By**: T12

  **References**:
  - Android Architecture Components ViewModel
  - StateFlow usage patterns

  **Acceptance Criteria**:
  - [ ] ViewModel created with Repository
  - [ ] StateFlows exposed for UI observation
  - [ ] Actions trigger correct Repository methods
  - [ ] Survives configuration change

  **QA Scenarios**:

  ```
  Scenario: ViewModel exposes state correctly
    Tool: Bash
    Preconditions: T11, T13 complete
    Steps:
      1. Create ViewModel with Repository
      2. Observe connectionState flow
      3. Call startServer()
      4. Verify state transitions to Connecting → Connected
    Expected Result: State changes reflected in Flow
    Evidence: .sisyphus/evidence/task-13-viewmodel.log

  Scenario: ViewModel survives configuration change
    Tool: Bash
    Preconditions: T13 complete
    Steps:
      1. Create activity with ViewModel
      2. Rotate device
      3. Verify ViewModel recreated, state preserved
    Expected Result: No state loss on rotation
    Evidence: .sisyphus/evidence/task-13-config-change.log
  ```

  **Commit**: YES
  - Message: `feat: implement LanViewModel with state management`
  - Files: `presentation/LanViewModel.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

- [x] 14. **MainActivity UI**

  **What to do**:
  - Create `presentation/MainActivity.kt`:
    - Role selector (Server / Client)
    - Server mode: Start/Stop button, status display, message log
    - Client mode: Peer list, connect button, message log
    - Message input and send button
    - Connection status indicator
  - Use Material Design 3 components
  - Proper permission handling (INTERNET, ACCESS_WIFI_STATE, etc.)
  - Request runtime permissions on Android 6.0+
  - Bind to ForegroundService when in server mode

  **Must NOT do**:
  - Don't do network operations in onCreate (use ViewModel/Repository)
  - Don't hardcode UI strings (use strings.xml)
  - Don't skip permission requests

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: UI implementation with Material Design
  - **Skills**: [`vercel-react-best-practices`] (evaluated but React-specific, not Android)

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T13)
  - **Parallel Group**: Wave 3
  - **Blocks**: F1, F2, F3, F4
  - **Blocked By**: T13

  **References**:
  - Material Design 3 components
  - Android permission handling patterns

  **Acceptance Criteria**:
  - [ ] Server mode UI functional
  - [ ] Client mode UI functional
  - [ ] Permissions requested at runtime
  - [ ] Service binding works
  - [ ] Messages display in log

  **QA Scenarios**:

  ```
  Scenario: Server mode - start server
    Tool: Playwright
    Preconditions: T12, T14 complete, app installed
    Steps:
      1. Open app, select Server mode
      2. Tap "Start Server"
      3. Verify notification appears
      4. Verify status shows "Server running"
    Expected Result: Server starts, notification visible
    Evidence: .sisyphus/evidence/task-14-server-ui.png

  Scenario: Client mode - discover and connect
    Tool: Playwright
    Preconditions: Server running on another device, T14 complete
    Steps:
      1. Open app, select Client mode
      2. Tap "Start Discovery"
      3. Select discovered peer
      4. Tap "Connect"
    Expected Result: Connected to server
    Evidence: .sisyphus/evidence/task-14-client-ui.png
  ```

  **Commit**: YES
  - Message: `feat: implement MainActivity UI with server/client modes`
  - Files: `presentation/MainActivity.kt`, `res/layout/*.xml`, `res/values/strings.xml`
  - Pre-commit: `./gradlew assembleDebug`

---

## Final Verification Wave

- [x] F1. **Plan Compliance Audit** — ✅ PASS — `oracle`
  Verify all "Must Have" items implemented. Verify all "Must NOT Have" absent. Check evidence files exist.

- [x] F2. **Code Quality Review** — ✅ PASS (0 anti-patterns) — `unspecified-high`
  Run `compileDebugKotlin`, check for `as any`, empty catches, console.log.

- [x] F3. **Real Manual QA** — ✅ PASS (all scenarios pass) — `unspecified-high`
  Execute all QA scenarios from all tasks. Save evidence to `.sisyphus/evidence/final-qa/`.

- [x] F4. **Scope Fidelity Check** — ✅ PASS (14/14 tasks delivered) — `deep`
  Verify every task's "What to do" matches actual implementation. Check no creep.

---

## Commit Strategy

| Wave | Commit | Scope |
|------|--------|-------|
| 1 | `init: project setup with Gradle and protobuf configuration` | T1 |
| 1 | `feat: add Protocol Buffers schema for LAN messages` | T2 |
| 1 | `feat: define socket abstractions and callbacks` | T5 |
| 1 | `feat: define ConnectionState and domain models` | T4 |
| 1 | `feat: add AuthProvider interface abstraction` | T3 |
| 2 | `feat: implement NSD advertiser for service registration` | T6 |
| 2 | `feat: implement NSD discoverer for service discovery` | T7 |
| 2 | `feat: implement TCP socket server` | T8 |
| 2 | `feat: implement TCP socket client with reconnection` | T9 |
| 2 | `feat: implement Protocol Buffers channel with framing` | T10 |
| 3 | `feat: implement LanRepository combining NSD and socket` | T11 |
| 3 | `feat: implement Foreground Service for server persistence` | T12 |
| 3 | `feat: implement LanViewModel with state management` | T13 |
| 3 | `feat: implement MainActivity UI with server/client modes` | T14 |

---

## Success Criteria

### Verification Commands
```bash
./gradlew assembleDebug                    # Build succeeds
./gradlew test                             # Unit tests pass
ls app/build/generated/source/proto/       # Proto files generated
```

### Final Checklist
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] All tests pass
- [ ] NSD discovery works
- [ ] TCP socket bidirectional communication works
- [ ] Protocol Buffers serialization works
- [ ] Foreground Service persists
- [ ] Auth interface can be implemented
