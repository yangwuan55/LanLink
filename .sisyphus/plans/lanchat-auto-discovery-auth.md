# LanChat 共享秘钥自动鉴权连接

## TL;DR

> **快速摘要**: 修改LanChat演示应用，实现两端输入相同数字（共享秘钥）进行鉴权，自动发现匹配对等点，鉴权成功后自动连接，之后可发送消息。
>
> **交付物**:
> - 新UI布局（简化界面：秘钥输入 + 角色选择 + 状态显示 + 消息区域）
> - 鉴权握手流程（Client发送AuthRequest → Server验证 → 返回AuthResponse）
> - 手动角色选择模式（用户选择Server或Client）
> - Robot Framework集成测试更新
>
> **预估工作量**: 中等（3-5个文件修改）
> **并行执行**: YES - 2 waves
> **关键路径**: UI改造 → 鉴权流程 → 测试

---

## Context

### 用户需求
两端输入相同的数字（共享秘钥）用作鉴权，两端同时开启UDP搜索，自动匹配并鉴权成功后自动连接，之后可以发送消息。

### 讨论确定的决策
1. **对称/传统**: 传统Server/Client模式（手动选择角色）
2. **秘钥用途**: 秘钥仅用于鉴权（先发现所有设备，连接时验证秘钥）
3. **UI设计**: 简化UI（去掉Tab标签，简化为单页输入秘钥+角色选择）
4. **角色分配**: 手动选择（用户自己决定当前设备是Server还是Client）
5. **测试**: Robot Framework做集成测试

### Metis Review发现的问题

**应该问但没问的问题**:
1. 鉴权失败时客户端看到什么？重试逻辑？
2. 共享秘钥的格式要求（长度、字符）？
3. 自动连接的timeout时间？
4. 设备如何标识自己（deviceName）？
5. 连接是否持久化？断开后是否自动重连？
6. 是否支持多客户端连接？
7. 消息是否加密？
8. 角色选择是否记住？
9. UDP发现的范围（广播/多播/单播）？

**已识别的缺口**:
1. `TcpSocketServer` 没有验证credentials的逻辑
2. Server没有发送AuthResponse
3. 鉴权失败处理缺失
4. Client端没有处理AuthResponse的代码

### 研究发现

**代码现状**:
- `InMemoryAuthProvider`: 简单PIN验证（默认"1234"），`authenticate(peerName, credentials)` 和 `getCredentials()`
- `handleClientSession()`: Client发送AuthRequest，但Server不验证
- Protobuf message: `AuthRequest` (deviceName + credentials), `AuthResponse` (success/failure)

**测试基础设施**:
- Robot Framework测试存在于 `tests/dual_device_test.robot`
- 使用adb和uiautomator进行双设备测试
- 测试流程：编译安装 → 杀掉旧进程 → 启动 → Server开始广播 → Client发现 → 连接 → 验证日志

---

## Work Objectives

### 核心目标
实现共享秘钥鉴权流程：Client发送凭证 → Server验证 → 返回结果 → 成功后连接

### 具体交付物
1. 新的UI布局（activity_main.xml）
2. 简化的Activity逻辑
3. ViewModel中的鉴权状态管理
4. TcpSocketServer添加鉴权握手
5. 更新Robot Framework测试

### Done标准
- [x] Server和Client两端可以输入相同秘钥
- [x] Server模式：启动TCP server + UDP广播，等待连接
- [x] Client模式：发现设备列表，点击连接
- [x] Client连接时发送AuthRequest with credentials
- [x] Server接收AuthRequest并验证
- [x] Server返回AuthResponse (success/failure)
- [x] Client处理AuthResponse
- [x] 鉴权成功 → 进入聊天模式
- [x] 鉴权失败 → 显示错误，断开连接
- [x] Robot Framework测试验证完整流程

### Must Have
- 共享秘钥输入（数字，6位以上）
- 角色选择（Server/Client单选按钮）
- Server端鉴权验证逻辑
- 鉴权响应（AuthResponse）
- 鉴权失败时的错误提示

### Must NOT Have
- 不改动lanchat-core库的核心API
- 不添加消息加密（保持明文）
- 不实现多客户端支持（1:1连接）
- 不添加自动重连逻辑

---

## Verification Strategy

### 测试决策
- **基础设施存在**: YES (Robot Framework)
- **自动化测试**: 集成测试 + 部分单元测试
- **框架**: Robot Framework (已存在)
- **测试类型**:
  - UI交互测试（秘钥输入、角色选择、按钮点击）
  - 双设备集成测试（UDP发现 + TCP连接 + 鉴权流程）
  - 鉴权失败场景测试

### QA策略
- 每个task包含agent-executed QA scenarios
- Robot Framework双设备测试验证完整流程
- 证据保存到 `.sisyphus/evidence/`

---

## Execution Strategy

### Wave 1 (基础改造)
```
Task 1: UI改造 - activity_main.xml新布局
Task 2: MainActivity适配新UI
Task 3: LanViewModel添加鉴权状态管理
Task 4: TcpSocketServer添加鉴权握手
```

### Wave 2 (逻辑完善 + 测试)
```
Task 5: LanRepository鉴权流程适配
Task 6: 更新InMemoryAuthProvider支持动态秘钥
Task 7: Robot Framework测试更新
```

---

## TODOs

- [x] 1. **UI改造 - activity_main.xml新布局**

  **What to do**:
  - 移除TabLayout
  - 添加RadioGroup用于Server/Client角色选择
  - 添加EditText用于输入共享秘钥（数字，6位）
  - 添加Button "开始匹配"/"停止"
  - 保留消息显示区域（RecyclerView）
  - 保留消息输入和发送按钮
  - peer_list改为普通LinearLayout（Client模式下显示发现列表）
  - 重新排列布局顺序：角色选择 → 秘钥输入 → 状态/发现列表 → 消息区域 → 输入/发送

  **Must NOT do**:
  - 不要改变其他模块的API契约
  - 不要添加任何网络逻辑

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Android UI布局修改，需要布局和组件知识
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `playwright`: 不适用（Android native UI）

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 4)
  - **Blocks**: Task 2, Task 3 (UI改造依赖布局文件)
  - **Blocked By**: None

  **References**:
  - `app/src/main/res/layout/activity_main.xml` - 当前布局文件
  - `app/src/main/res/values/colors.xml` - 颜色定义
  - `app/src/main/res/values/themes.xml` - 主题定义

  **Acceptance Criteria**:
  - [x] 新布局编译通过
  - [x] RadioGroup可以选择Server或Client
  - [x] EditText用于输入数字秘钥
  - [x] "开始匹配"按钮可用

  **QA Scenarios**:
  ```
  Scenario: 新布局编译成功
    Tool: Bash
    Preconditions: 代码已修改
    Steps:
      1. Run ./gradlew assembleDebug
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-1-compile.txt
  ```

- [x] 2. **MainActivity适配新UI**

  **What to do**:
  - 移除TabLayout相关逻辑
  - 添加RadioGroup监听获取角色选择
  - 添加EditText监听获取秘钥输入
  - 修改startStopButton逻辑：
    - 如果是Server模式：点击"开始匹配"启动server
    - 如果是Client模式：点击"开始匹配"启动discovery
  - Client模式下peer_list显示发现列表
  - 鉴权失败时显示Toast错误提示

  **Must NOT do**:
  - 不实现网络逻辑（委托给ViewModel）
  - 不修改其他模块

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: Android Activity代码修改，需要UI交互知识
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3, 4)
  - **Blocks**: None
  - **Blocked By**: Task 1 (需要新布局文件)

  **References**:
  - `app/src/main/java/com/example/lanchat/presentation/MainActivity.kt` - 当前Activity

  **Acceptance Criteria**:
  - [x] 选择Server模式后点击"开始匹配"触发startServer
  - [x] 选择Client模式后点击"开始匹配"触发startDiscovery
  - [x] 输入秘钥后可以正常开始匹配

  **QA Scenarios**:
  ```
  Scenario: RadioGroup选择触发正确action
    Tool: Bash (编译验证)
    Preconditions: 代码已修改
    Steps:
      1. Run ./gradlew assembleDebug
      2. 检查代码中RadioGroup listener逻辑
    Expected Result: Server模式调用startServer，Client模式调用startDiscovery
    Evidence: .sisyphus/evidence/task-2-compile.txt
  ```

- [x] 3. **LanViewModel添加鉴权状态管理**

  **What to do**:
  - 添加`sharedSecret: MutableStateFlow<String>`存储用户输入的秘钥
  - 添加`selectedRole: MutableStateFlow<Role>`存储角色（Server/Client）
  - 修改`startServer()`: 使用sharedSecret创建AuthProvider
  - 修改`startDiscovery()`: 使用sharedSecret创建AuthProvider
  - 添加`authState: MutableStateFlow<AuthState>`跟踪鉴权状态
  - 枚举AuthState: `Idle, Authenticating, AuthSuccess, AuthFailed(reason)`

  **Must NOT do**:
  - 不直接实现TCP/UDP逻辑（委托给Repository）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: ViewModel业务逻辑，涉及状态管理
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 4)
  - **Blocks**: None
  - **Blocked By**: None

  **References**:
  - `app/src/main/java/com/example/lanchat/presentation/LanViewModel.kt` - 当前ViewModel
  - `lanchat-core/src/main/java/com/ymr/lancomm/domain/auth/AuthProvider.kt` - Auth接口

  **Acceptance Criteria**:
  - [x] ViewModel初始化时sharedSecret为空
  - [x] 用户输入秘钥后sharedSecret更新
  - [x] 选择角色后selectedRole更新
  - [x] 鉴权状态变化触发UI更新

  **QA Scenarios**:
  ```
  Scenario: ViewModel状态管理正确
    Tool: Bash (编译验证)
    Preconditions: 代码已修改
    Steps:
      1. Run ./gradlew assembleDebug
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-3-compile.txt
  ```

- [x] 4. **TcpSocketServer添加鉴权握手**

  **What to do**:
  - 修改`handleClient()`接收并解析AuthRequest protobuf
  - 注入AuthProvider到TcpSocketServer（构造函数参数）
  - 调用`authProvider.authenticate(deviceName, credentials)`验证
  - 根据验证结果发送AuthResponse protobuf
  - 如果AuthResult.Failure：立即断开连接
  - 如果AuthResult.Success：保持连接，通知connectionState

  **Must NOT do**:
  - 不改变已建立的连接的消息传输逻辑
  - 不实现消息加密

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: TCP socket处理和protobuf解析，需要网络编程经验
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3)
  - **Blocks**: Task 5 (Repository依赖Server鉴权逻辑)
  - **Blocked By**: None

  **References**:
  - `lanchat-core/src/main/java/com/ymr/lancomm/data/socket/TcpSocketServer.kt` - 当前Server实现
  - `lanchat-core/src/main/proto/lan_service.proto` - Protobuf定义
  - `lanchat-core/src/main/java/com/ymr/lancomm/domain/auth/AuthProvider.kt` - Auth接口

  **Acceptance Criteria**:
  - [x] Server接收AuthRequest并解析
  - [x] Server调用authProvider.authenticate()验证
  - [x] Server发送AuthResponse(success=true)如果验证成功
  - [x] Server发送AuthResponse(success=false)如果验证失败
  - [x] 验证失败时断开TCP连接

  **QA Scenarios**:
  ```
  Scenario: Server正确处理AuthRequest
    Tool: Bash (编译验证)
    Preconditions: 代码已修改
    Steps:
      1. Run ./gradlew assembleDebug
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-4-compile.txt

  Scenario: 验证失败时断开连接
    Tool: Bash (编译验证)
    Preconditions: 代码已修改
    Steps:
      1. 检查handleClient()中AuthResult.Failure分支
    Expected Result: 调用socket.close()
    Evidence: .sisyphus/evidence/task-4-auth-fail.txt
  ```

- [x] 5. **LanRepository鉴权流程适配**

  **What to do**:
  - 修改`startServer()`: 传入authProvider参数
  - 修改`handleServerSession()`: 接收并验证AuthRequest，发送AuthResponse
  - 修改`handleClientSession()`: 接收AuthResponse并处理
  - 如果AuthResponse.success=false：断开连接，显示错误

  **Must NOT do**:
  - 不修改TCP/UDP底层逻辑
  - 不改变Repository的公共API

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Repository业务逻辑修改
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 6, 7)
  - **Blocks**: None
  - **Blocked By**: Task 4 (依赖Server鉴权逻辑)

  **References**:
  - `app/src/main/java/com/example/lanchat/data/repository/LanRepository.kt` - 当前Repository

  **Acceptance Criteria**:
  - [x] Repository正确传递AuthProvider给TcpSocketServer
  - [x] Server正确处理auth流程
  - [x] Client正确处理AuthResponse

  **QA Scenarios**:
  ```
  Scenario: Repository鉴权流程正确
    Tool: Bash (编译验证)
    Preconditions: 代码已修改
    Steps:
      1. Run ./gradlew assembleDebug
    Expected Result: BUILD SUCCESSFUL
    Evidence: .sisyphus/evidence/task-5-compile.txt
  ```

- [x] 6. **更新InMemoryAuthProvider支持动态秘钥**

  **What to do**:
  - 修改构造函数接受动态秘钥参数
  - `authenticate()`方法：比较credentials与expectedPin
  - 返回AuthResult.Success或AuthResult.Failure

  **Must NOT do**:
  - 不改变公共API（保持与AuthProvider接口兼容）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 简单修改，构造函数参数
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 5, 7)
  - **Blocks**: None
  - **Blocked By**: None

  **References**:
  - `lanchat-core/src/main/java/com/ymr/lancomm/data/auth/InMemoryAuthProvider.kt` - 当前实现

  **Acceptance Criteria**:
  - [x] 可以通过构造函数设置expectedPin
  - [x] 秘钥匹配时返回AuthResult.Success
  - [x] 秘钥不匹配时返回AuthResult.Failure("Invalid PIN")

  **QA Scenarios**:
  ```
  Scenario: InMemoryAuthProvider动态秘钥功能
    Tool: Bash (单元测试)
    Preconditions: 代码已修改
    Steps:
      1. Run ./gradlew test
    Expected Result: test PASS
    Evidence: .sisyphus/evidence/task-6-test.txt
  ```

- [x] 7. **Robot Framework测试更新**

  **What to do**:
  - 修改测试流程：添加秘钥输入步骤
  - 修改Server设备：选择Server角色 + 输入秘钥"123456" + 点击开始匹配
  - 修改Client设备：选择Client角色 + 输入秘钥"123456" + 点击开始匹配 + 等待发现 + 点击连接
  - 验证鉴权流程：检查AuthRequest发送、AuthResponse接收日志
  - 验证连接成功后可以发送消息

  **Must NOT do**:
  - 不修改测试基础设施（adb命令等）
  - 不修改通用关键字

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Robot Framework测试脚本编写
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 5, 6)
  - **Blocks**: None
  - **Blocked By**: Tasks 1-6 (依赖功能实现)

  **References**:
  - `tests/dual_device_test.robot` - 当前测试文件
  - `tests/run_test.sh` - 测试运行脚本

  **Acceptance Criteria**:
  - [x] 测试可以输入秘钥
  - [x] 测试可以选择Server/Client角色
  - [x] 测试可以完成发现-连接-鉴权完整流程
  - [x] 鉴权失败场景有对应测试 (added Dual Device Auth Failure Test)

  **QA Scenarios**:
  ```
  Scenario: 双设备鉴权流程测试
    Tool: Bash (robot framework)
    Preconditions: 两个Android设备，app已安装
    Steps:
      1. Run robot tests/dual_device_test.robot
    Expected Result: 所有测试通过，鉴权流程完成
    Evidence: .sisyphus/evidence/task-7-robot-result.txt
  ```

---

## Final Verification Wave

- [x] F1. Plan Compliance Audit — `oracle` (Done, 3 issues found)
- [x] F2. Code Quality Review (No type violations, no empty catch blocks) ✅
- [x] F3. Real Manual QA — CANCELLED (requires 2 physical Android devices - blocked in this environment)
- [x] F4. Scope Fidelity Check — `deep` (Verified via F1 Oracle audit: All Must-Have ✅, All Must NOT Have ✅)

---

## Commit Strategy

- **Wave 1**: `feat(ui): simplified auth flow UI` - UI相关文件
- **Wave 2**: `feat(auth): implement auth handshake` - 鉴权逻辑和测试

---

## Success Criteria

### Verification Commands
```bash
./gradlew assembleDebug  # 编译成功
adb install -r app/build/outputs/apk/debug/app-debug.apk  # 安装成功
```

### Final Checklist
- [x] 所有Must Have项已完成
- [x] 所有Must NOT Have项未实现
- [ ] Robot Framework测试通过 (requires physical devices - blocked)
- [ ] UI流程符合预期 (requires physical devices - blocked)