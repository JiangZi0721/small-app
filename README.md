# ShakeGuard
## 备份说明

本目录是 `ShakeGuard` 最新完整实现的可发布前备份副本，备份目标为 `JiangZi0721/small-app` 的独立分支 `shakeguard-backup-20260830`。截至 2026-08-30，备份基线对应本地分支 `feature/feedback-notifications` 的提交 `10bbe9205c0d8420debfebc38c1bc1e99d3a7577`（`docs: record recovery validation command`）。

选择这个基线而不是根目录 `main` 的原因是：根目录 `main` 仍停留在只有核心保护逻辑的早期版本，而该基线已经包含通知反馈、来源选择、规则管理、活动记录和可操作 Compose UI。项目文件共 93 个，构建缓存、IDE 文件、`local.properties`、设备密钥和 APK 均未纳入版本控制。

远端 `main` 当前保留的是仓库中的其他 `vision-skill` 项目，因此本次不覆盖或合并 `main`。如果以后从 GitHub 恢复 ShakeGuard，直接检出 `shakeguard-backup-20260830`，再从该分支建立新的功能分支；不要把 `app/build/`、`.gradle/` 或本机 `local.properties` 提交回仓库。

## 项目结构

```text
ShakeGuard/
├─ app/
│  ├─ src/main/java/com/shakeguard/
│  │  ├─ accessibility/   # 无障碍服务、窗口事件适配、回退执行
│  │  ├─ data/            # Room、DataStore、Repository、事务
│  │  ├─ feedback/        # 一次性允许和用户反馈用例
│  │  ├─ notifications/   # 拦截通知和反馈广播接收器
│  │  ├─ protection/      # 领域模型、会话、规则、协调器
│  │  └─ ui/              # Compose 页面、导航、ViewModel
│  ├─ src/test/           # 纯 Kotlin/JVM 单元测试
│  ├─ src/androidTest/    # Room、Manifest、Compose 和设备测试
│  └─ schemas/            # Room v1/v2 schema 与迁移基线
├─ docs/superpowers/
│  ├─ specs/              # 需求、边界和交互设计
│  └─ plans/              # 分阶段实现计划
├─ gradle/
│  ├─ libs.versions.toml  # Gradle Version Catalog
│  └─ wrapper/             # Gradle Wrapper 8.10.2
├─ build.gradle.kts       # 插件声明
├─ settings.gradle.kts    # 仓库、模块和项目名
└─ README.md              # 技术说明、验证记录和恢复指南
```

### 模块职责

| 模块 | 核心职责 | 关键学习点 |
| --- | --- | --- |
| `accessibility` | 监听前台窗口并在命中保护规则后执行返回 | Android 系统边界、服务生命周期、事件去重 |
| `protection` | 管理保护会话并按优先级产生 `ALLOW`、`OBSERVE`、`BLOCK` | 纯 Kotlin 领域建模、状态机、可测试时钟 |
| `data` | 持久化来源、规则、事件和设置 | Room 映射、迁移、事务、Repository 分层 |
| `feedback` | 将通知操作转换为可回滚的业务命令 | Mutex、幂等写入、取消传播 |
| `notifications` | 发布首次阻断通知并接收四种反馈操作 | Android 13 权限、PendingIntent、异常隔离 |
| `ui` | 提供首页、来源、规则和活动记录管理 | Compose 状态提升、StateFlow、导航和 ViewModel |

ShakeGuard 是一个 Android-only 的本地原型，用于在“摇一摇”广告已经触发跨应用跳转后，尽快识别目标应用并执行返回动作。它面向普通用户，不依赖 root、LSPosed、VPN、云端服务或遥测。

## 先看边界

普通 Android 应用不能阻止另一个应用读取陀螺仪或加速度计，也不能在系统层拦截任意显式 `startActivity()`。因此 ShakeGuard 不能承诺“跳转前完全阻止”或“目标页面绝不闪现”。无障碍服务只能在观察到前台窗口变化后尽快执行返回。

当前边界如下：

- 只支持 Android，不提供 iOS、桌面端或云端同步。
- 不使用 root、LSPosed、VPN、代理、远程模型或遥测。
- 规则只在用户为来源应用配置的保护时间窗内生效；时间窗外放行，也不产生保护命中记录。
- 无障碍服务只使用窗口事件中的包名和类名，不读取窗口正文、输入字段、通知内容或网页 URL。
- 保护动作发生在跳转之后。返回失败会被记录，但不会无限循环发送返回动作；普通系统返回确认失败时，服务最多尝试一次重新启动受保护来源应用。
- 通知权限是可选能力。通知无法发布时，已经完成的保护决定和本地事件记录不受影响。

## 当前实现

### 规则与会话

`AccessibilityService` 将支持的窗口事件交给 `AccessibilityEventAdapter`，适配器只复制包名和类名。`SessionTracker` 维护当前受保护来源、会话起点、恢复状态和短时重复目标去重；单调时钟回拨时，耗时不会变成负数。

`ProtectionCoordinator` 的决策顺序是：

1. 系统界面、输入法、启动器、ShakeGuard 自身或同包跳转等排除项，得到 `IGNORE`。
2. 来源未配置、来源被禁用或超出用户时间窗，得到 `ALLOW`。
3. 精确的“一次性允许” token 优先于普通规则，得到 `ALLOW`，原因固定为 `one-time allowance`。
4. 来源级 BLOCK 规则。
5. 精确 source-target 的 BLOCK 规则。
6. 没有规则命中时得到 `OBSERVE`，记录观察事件但不执行返回。

命中 `BLOCK` 时最多执行一次有界的系统返回确认；如果目标页面（例如某些第三方 `LightBoxActivity`）不响应普通返回，服务会使用事件中的 exact source package 查找 launch intent，以 `FLAG_ACTIVITY_NEW_TASK` 最多重新启动来源一次，并再次确认前台包已离开目标。只有确认离开目标才算 `RETURNED`；无 launch intent、启动异常或仍停留目标都记录为 `RETURN_FAILED`。这不是对第三方页面的强制关闭，也不保证 OEM/应用自定义任务栈一定接受来源重启。`BLOCK` 与 `OBSERVE` 会写入事件并保留 Room 生成的 `eventId`；`ALLOW`、`IGNORE` 和未记录路径的 `eventId` 为 `null`。

### 一次性允许

`OneTimeAllowanceStore` 是进程内、Mutex 保护的内存 token 存储：

- key 是精确的 `sourcePackage + targetPackage`，不同来源或目标不能消费。
- token 有效期为 30 秒，恰好 30 秒仍有效。
- 下一次相同 source-target 匹配消费一次后立即删除。
- 单调时钟回拨会使 token 失效并删除，避免回拨造成越权放行。
- 反馈持久化失败时，`FeedbackHandler` 会在同一 Mutex 内用 `NonCancellable` 撤销刚授予的 exact pair token。

### Room 与反馈数据层

Room 当前为 v2。v1 到 v2 的迁移为 `protected_sources` 增加 `createdAt`，并用旧的 `updatedAt` 回填。`RuleRepository` 将 Room entity 转换为保护领域对象；`RoomProtectionEventRecorder` 使用 Room 插入返回的 Long 作为保护事件 ID。

反馈数据层通过 `FeedbackRepository` 暴露以下语义：

- 用 event ID 精确查找 source-target。
- `ALLOW_ONCE` 只更新事件反馈，token 由内存 store 管理。
- `ALLOW_PAIR` 在事务中更新事件并对精确 source-target 的 `ALLOW` pair rule 幂等 upsert。
- `AD` 在事务中更新事件并对精确 source-target 的 `BLOCK` pair rule 幂等 upsert。
- `STOP_PROTECTING_SOURCE` 在事务中禁用来源并更新事件；保留来源的 `windowMs`、`createdAt`、`sourceLevelBlock` 和其他 pair rule。
- source 或 event 不存在时返回可观察的失败，不假装反馈成功。
- 精确 source-target、`decision = 'BLOCK'` 的数量查询用于判断是否是第一次阻断。

涉及事件和规则或事件和来源的多表反馈写入，都通过 `RoomDatabase.withTransaction` 保证不会留下半写入状态。重复反馈复用已有 rule ID，不会不断增加相同 pair 的规则行。

### FeedbackHandler

`FeedbackHandler` 是纯 Kotlin 的共享用例入口，处理 `AllowOnce`、`AllowPair`、`ConfirmAd` 和 `StopProtectingSource` 四种命令。

- 整个 `handle` 流程在同一个 `kotlinx.coroutines.sync.Mutex` 内串行，包括 event target 查询、持久化和 token 回滚。
- 先用 event ID 查询精确 target；事件不存在返回 `NotFound`，不写任何数据。
- 所有持久化 action 使用可注入的 epoch clock，生产环境使用系统时间。
- 普通持久化异常返回 `Failed` 并保留原始 cause。
- `CancellationException` 不会被转换为失败结果；AllowOnce 取消时仍在 `NonCancellable` 中撤销 exact token，然后重新抛出原取消异常。

### 进程级依赖图

`ShakeGuardApplication` 以 lazy typed properties 持有并共享：

- `AppDatabase`；
- 使用同一数据库 DAO 的 `RuleRepository`；
- `OneTimeAllowanceStore`；
- 使用 `RoomFeedbackRepository`、同一 allowance store 的 `FeedbackHandler`；
- `ProtectionNotificationManager`。

AccessibilityService 只拥有自己的单线程 dispatcher 和 service scope，不创建第二份 Room、Repository 或 allowance store，也不会在销毁时关闭 Application 共享的数据库。

### 通知与反馈接收器

通知 channel ID 为 `protection_feedback`。通知内容只显示 source/target 应用 label，解析不到 label 时回退到包名。通知有四个 action：

- `ALLOW_ONCE`：只允许下一次 exact source-target 跳转；
- `ALLOW_PAIR`：持久化长期允许 pair rule；
- `CONFIRM_AD`：记录为广告并持久化 BLOCK pair rule；
- `STOP_PROTECTING_SOURCE`：禁用来源保护。

每个 action 都是指向本应用 `FeedbackReceiver` 的 explicit、immutable `PendingIntent`，只携带 event ID 和 action 身份，不携带窗口正文、输入内容或通知文本。receiver 在 Manifest 中声明为 `exported=false`，只接受四个内部 action、正数 event ID 和指向自身的 component。合法 intent 通过 `goAsync()` 启动有 8 秒上限的协程，最终在 `finally` 中调用 `PendingResult.finish()`。

Android 13 及以上如果 `POST_NOTIFICATIONS` 被拒绝，或系统通知开关关闭，manager 返回 `false` 且不调用 notify。channel 创建、label 解析、notification 构建和发布中的 Android runtime failure 也返回 `false`。Task 8 的 Service 将通知查询和发布放在独立异常边界内，因此通知失败、权限拒绝或数据库查询异常不会改变已得到的保护 outcome。

只有精确 source-target 的第一条 `BLOCK` 事件会发布通知。第二条及之后的同 pair BLOCK 只保留保护事件，不重复发布通知；`OBSERVE`、`ALLOW`、`IGNORE`、时间窗外放行、一次性允许和空 event ID 都不会触发通知。应用不会自动启动第三方目标应用。

当第三方目标页面不响应普通 Back（例如某些 LightBox 页面）时，来源重启只是有界兜底：它只使用用户已经配置的 source package、只调用一次，并等待无障碍前台事件确认目标已离开。该兜底可能受 Android 任务栈、OEM 后台限制或来源应用自身启动策略影响；失败不会伪造为成功，也不会读取窗口正文、输入或网络内容。

### 可操作 UI

当前 Compose 原型提供首页、规则和活动三项底部导航。首页展示无障碍授权、全局保护开关、来源配置状态和通知可选能力；规则页支持全部/允许/阻止/冲突筛选、独立启停、删除确认和新增/编辑；活动页展示最近事件、事件详情和四种反馈，并在确认后清空全部事件。来源选择页读取可启动应用目录，来源详情页保存包名、时间窗、来源级阻断和暂停/恢复状态。

页面通过 `StateFlow` 驱动，不直接访问 Room DAO。导航目的地使用生命周期管理的 ViewModel factory，并将 Application context 传给会跨配置存活的目录适配器。系统设置 Intent 无法解析时会被隔离，避免影响保护流程。通知拒绝或通知发布失败只显示可选能力不可用，不替换首页的保护状态。

## 隐私与本地数据

当前记录的保护事件字段是 source 包名、target 包名、耗时、决策、匹配规则 ID、返回结果、用户反馈和创建时间。规则数据包含来源配置、时间窗、来源级阻止状态以及 source-target pair rule。数据保存在本地 Room，不上传网络，也没有遥测。

服务不读取窗口正文、输入字段、通知文本或网络内容。活动页的“清空全部记录”经过确认后只删除 `jump_events`，不会删除来源、时间窗或规则。当前代码还没有自动保留策略或独立隐私设置页面，因此不把它们描述为现成产品功能。

## 测试与验证方法

### TDD 测试结构

实现过程遵循 RED -> GREEN：先用最小测试表达契约，再让编译或断言失败，之后写最小实现并回归。测试重点包括：

- JVM fake 测试：规则优先级、时间窗、SessionTracker 去重、一次性 token、ProtectionCoordinator、FeedbackHandler、依赖图、通知 action 契约和 Task 8 首次阻断 helper。
- Room instrumentation：真实 SQLite 查询、v1 -> v2 migration、event ID、反馈更新、事务、幂等 pair rule 和精确 BLOCK count。
- 通知契约：四个 action 到四个 FeedbackCommand 的映射、malformed intent 拒绝、正数 event ID、request code 稳定性，以及 manager 的权限/RuntimeException fallback。
- Service 相关测试：只验证纯 helper 的 exact source-target 首次 BLOCK 通知决策；保护 outcome 不因通知失败改变。
- Compose instrumentation 源码编译测试：验证 ActivityLog 空状态/清空入口和 ProtectionDisabled 稳定文案；当前环境没有可执行设备，因此没有把它写成真机通过。

事务用于把多表写入变成一个原子操作；Room insert 返回 Long 用来精确定位反馈事件；Mutex 用来串行化内存 token 和 handler；Android 13 permission check 用来把通知能力降级为可选能力。这些是本项目最值得复习的 Kotlin 并发、数据库一致性和 Android 生命周期边界。

### 当前 HEAD 的真实验证矩阵

验证基线：`3bb4b7d59e009734571ea8d399b7f97cd4086643`。

fresh 命令：

```powershell
$env:JAVA_HOME = "G:\Java7\JDK17\jdk-17.0.20.8-hotspot"
$env:ANDROID_SDK_ROOT = "F:\Andriod-Studio\Sdk"
$env:GRADLE_USER_HOME = "C:\Users\zero\AppData\Local\Temp\shakeguard-task1-gradle-home"
$env:ANDROID_USER_HOME = "F:\Codex-app\shakeguard-task9-android-home"
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
```

当前 fresh full JVM 结果为 121 tests，所有 suite 的 failures/errors/skipped 都是 0：

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `FirstBlockNotificationTest` | 10 | 0 | 0 | 0 |
| `RuleRepositoryTest` | 24 | 0 | 0 | 0 |
| `FeedbackHandlerTest` | 12 | 0 | 0 | 0 |
| `OneTimeAllowanceStoreTest` | 9 | 0 | 0 | 0 |
| `FeedbackNotificationContractTest` | 4 | 0 | 0 | 0 |
| `ProtectionCoordinatorTest` | 9 | 0 | 0 | 0 |
| `ProtectionGateTest` | 2 | 0 | 0 | 0 |
| `RuleEvaluatorTest` | 14 | 0 | 0 | 0 |
| `SessionTrackerTest` | 12 | 0 | 0 | 0 |
| `ShakeGuardApplicationTest` | 6 | 0 | 0 | 0 |
| `ActivityLogViewModelTest` | 3 | 0 | 0 | 0 |
| `HomeViewModelTest` | 3 | 0 | 0 | 0 |
| `RulesViewModelTest` | 3 | 0 | 0 | 0 |
| `SourcesViewModelTest` | 9 | 0 | 0 | 0 |
| `UiDependencyContractTest` | 1 | 0 | 0 | 0 |
| **Total** | **121** | **0** | **0** | **0** |

Task 7 runtime-exception 修复后的历史 JVM 基线是 76 tests，失败/错误/跳过均为 0；后续 Task 8-12 增加了来源、规则、ProtectionGate、ActivityLog 和 UI 合约测试，当前总数为 121。历史数字用于解释变化，不替代当前 fresh 验证。

AndroidTest 编译命令：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。当前 `:app:compileDebugAndroidTestKotlin` 和 `:app:assembleDebug` 均成功，使用仓库外 `GRADLE_USER_HOME` 与 `ANDROID_USER_HOME`。构建中有 SDK XML v4 compatibility 提示、既有 Kotlin/Android API deprecated warnings，以及 `libandroidx.graphics.path.so`、`libdatastore_shared_counter.so` 无法 strip 而直接打包的 warning；这些没有阻止 debug APK 生成。

connected instrumentation 已尝试一次。APK 编译和打包阶段完成，但 ADB 报：

```text
Cannot mkdir '\.android': Permission denied
Could not create ADB Bridge
```

因此设备执行为 0 cases，不能写成 instrumentation 通过。当前没有真实设备权限撤销场景证据；Android 13+ 通知拒绝仅有 JVM contract/fake 和异常边界覆盖，尚未在真机撤销权限后验证保护流程。

## 已知风险与后续路线

当前实现仍是可验证的核心和反馈基础设施，不是完整可发布产品。后续至少需要：

- Compose onboarding、首次授权引导和更完整的用户帮助；
- 自动事件保留策略、独立隐私设置和数据导出/删除说明；
- source-app/target-app 测试 APK、跨应用自动化和真实手机 E2E；
- Android 真机 Room、通知 channel、四 action、权限拒绝和 receiver 生命周期验证；当前仅有编译和 JVM/fake 边界证据；
- 不同 OEM 的无障碍保活、通知权限、后台限制和排障说明；
- request code 对整个 Long 域不是数学单射，但 PendingIntent 的 data URI 仍包含完整 event ID 和 action ordinal；
- `ShakeGuardApplication` lazy graph 的初始化异常边界目前是 Minor 风险，需要产品化时补充更明确的故障呈现；
- 完整隐私说明、测试矩阵、release 签名和发布构建流程。

不要把旧 README 中声称已经在真机通过的记录当作当前 Task 9 证据；本文件的验证矩阵只记录当前可复现命令的实际结果。

## 恢复开发

```powershell
$env:JAVA_HOME = "G:\Java7\JDK17\jdk-17.0.20.8-hotspot"
$env:ANDROID_SDK_ROOT = "F:\Andriod-Studio\Sdk"
$env:GRADLE_USER_HOME = "C:\Users\zero\AppData\Local\Temp\shakeguard-task1-gradle-home"
$env:ANDROID_USER_HOME = "F:\Codex-app\shakeguard-task9-android-home"
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --no-daemon --console=plain
```

如果默认 Android 用户目录的 debug keystore lock 被其他进程占用，只能改用仓库外的临时目录并记录结果；不要删除锁、终止其他 Gradle/ADB 进程，也不要在项目内创建 `.gradle-user-home`。
