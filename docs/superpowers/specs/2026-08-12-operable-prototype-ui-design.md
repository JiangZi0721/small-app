# ShakeGuard 可操作原型 UI 设计规格

日期：2026-08-12
状态：已获批准，作为 UI 实现前的设计基线
适用平台：Android only，最低 Android API 29
实现方式：单 Activity + Jetpack Compose + Compose Navigation

## 1. 文档目的

本文定义 ShakeGuard 可操作原型的完整 UI、数据流、运行时契约和验收边界。它服务于普通 Android 用户，不要求用户理解包名、Intent、无障碍事件或规则评估器的内部实现。用户应能通过应用名称、开关、时间窗和明确的反馈文案完成配置。

本文是设计规格，不包含实现代码。实现必须遵守本文的产品契约、状态语义、数据保留规则和测试边界。若实现需要改变这些行为，必须先更新本规格并重新获得批准。

## 2. 目标与非目标

### 2.1 目标

- 提供可操作的首页，明确显示保护是否实际生效。
- 提供无障碍服务授权引导和返回后的状态刷新。
- 提供可选的通知权限引导，不让通知能力影响核心保护。
- 提供来源应用选择、来源暂停/恢复和每来源时间窗设置。
- 提供来源级阻止和 source-target 组合规则管理。
- 提供最近 100 条 ActivityLog。
- 为 BLOCK 事件提供四种可解释、可撤销或可持久化的反馈操作。
- 支持二次确认后清空全部跳转事件，同时保留来源和规则配置。
- 保持所有数据本地化，限制无障碍服务读取的数据范围。

### 2.2 非目标

本阶段不实现以下能力：

- root、Magisk、LSPosed、Xposed 或系统级 Intent 拦截。
- 在第三方应用跳转前保证绝对阻止。
- 读取窗口正文、输入框、通知正文、网页 URL 或网络内容。
- 云端黑名单、用户行为上传、远程同步或机器学习训练。
- 自动恢复第三方原始 Intent 并重新启动目标应用。
- 自动打开第三方目标应用。
- 通过删除来源来表达暂停。
- 通过清空 ActivityLog 删除来源、时间窗或规则。

## 3. 普通用户体验原则

### 3.1 文案原则

界面优先使用普通用户可理解的中文：

- 使用“来源应用”“目标应用”“保护中”“已暂停”“允许一次”“允许此组合”等文案。
- 包名只作为详情或排障辅助信息，不作为主要识别文本。
- 使用“时间窗”时同时显示具体数值和单位，例如“5 秒”。
- 将无障碍服务描述为“系统授权”，说明这是 Android 的必要设置。
- 将通知描述为“可选提醒”，明确拒绝通知不会关闭保护。

### 3.2 低门槛配置路径

首次打开或未完成配置时，首页引导顺序如下：

1. 说明 ShakeGuard 只观察应用切换的包名和 Activity 类名，不读取页面正文。
2. 引导用户打开系统无障碍设置。
3. 用户返回应用后重新检测授权状态。
4. 引导用户选择来源应用。
5. 为来源确认时间窗和来源级阻止设置。
6. 以非阻塞方式建议开启通知权限。

用户可以跳过通知引导，也可以稍后从首页重新进入。通知引导不能阻塞来源配置和核心保护。

### 3.3 Android-only 约束

本产品只针对 Android API 29 及以上设计。界面和状态模型必须基于 Android 的：

- AccessibilityService 绑定状态。
- `Settings.ACTION_ACCESSIBILITY_SETTINGS` 系统设置跳转。
- Android 13 及以上的 `POST_NOTIFICATIONS` 运行时权限。
- `PackageManager` 应用列表和应用信息。
- Room 本地数据库。
- DataStore Preferences 本地设置。

不设计 iOS、桌面端或跨平台抽象。系统能力异常时必须展示降级状态，不能伪造“保护中”。

## 4. 已批准的产品契约

### 4.1 全局保护开关

全局保护开关关闭时：

- 无障碍服务保持绑定。
- 新的前台跳转直接放行。
- 不执行 `Back`。
- 不创建 `BLOCK` 保护事件。
- 不发送首次阻断通知。
- 不删除既有 ActivityLog。
- 不删除来源、时间窗、来源级设置或组合规则。

重新打开后，下一个新跳转立即恢复正常保护决策。不需要重新绑定服务，也不需要重启应用。

首页必须将“保护开关关闭”和“无障碍服务未授权”区分为两个状态。前者表示服务仍可能绑定但保护逻辑暂停，后者表示系统授权尚未生效。

### 4.2 三项底部导航

主导航固定为三项：

```text
首页 / 规则 / 活动
```

来源选择、来源详情和规则编辑作为子页面，不增加第四个底部导航项。

### 4.3 来源状态

来源管理页面必须显示所有已保存来源，包括启用和禁用来源。禁用来源的展示状态为“已暂停”。

已暂停来源必须保留：

- 来源包名。
- 应用名称和图标的运行时解析能力。
- 时间窗。
- 来源级阻止设置。
- 所有 source-target 组合规则。
- 创建时间和更新时间。

恢复保护只将来源重新设为启用，不重置时间窗，不删除规则，不创建重复配置。

### 4.4 组合规则冲突

同一 `sourcePackage + targetPackage` 允许同时存在一条 `ALLOW` 规则和一条 `BLOCK` 规则。UI 必须显示冲突，但不得自动删除、覆盖或合并其中任何一条。

两种规则必须可以分别：

- 启用。
- 停用。
- 编辑。
- 删除。

实际决策优先级由现有保护引擎负责，UI 不复制或替代引擎的规则评估逻辑。

### 4.5 ActivityLog 与清空

- 默认显示最近 100 条跳转事件。
- 清空操作删除 `jump_events` 中的全部事件。
- 清空不影响来源、来源启用状态、时间窗、来源级阻止设置和 `pair_rules`。
- 清空前必须显示确认对话框。
- 清空成功后由数据流驱动页面进入空状态。
- 清空失败时保留当前列表，并显示失败反馈。

### 4.6 四种反馈

ActivityLog 对符合条件的 BLOCK 事件提供四种操作：

| 用户文案 | 领域命令 | 行为 |
| --- | --- | --- |
| 允许这一次 | `AllowOnce` | 为精确 source-target 创建一次性内存许可，并更新事件反馈 |
| 总是允许此组合 | `AllowPair` | 持久化启用的 `ALLOW` 组合规则 |
| 确认是广告 | `ConfirmAd` | 持久化启用的 `BLOCK` 组合规则 |
| 不再保护此来源 | `StopProtectingSource` | 暂停来源，保留来源配置和组合规则 |

四种操作都必须调用同一个 `FeedbackHandler`。通知操作和 ActivityLog 操作不能各自实现一套写库逻辑。

## 5. 总体架构

### 5.1 模块划分

应用使用单 Activity 承载 Compose 内容，页面通过 Navigation 管理。推荐模块关系如下：

```text
MainActivity
  -> ShakeGuardApp
    -> AppNavHost
      -> HomeScreen / RulesScreen / ActivityLogScreen
      -> SourcePickerScreen / SourceDetailScreen / RuleEditorScreen

Screen
  -> ViewModel
    -> Repository / FeedbackHandler / System Adapter
      -> Room DAO / DataStore / PackageManager / Android system settings
```

页面 Composable 只接收不可变 UI state 和用户事件回调。页面不能直接创建 Room、DataStore、PackageManager 查询或 FeedbackHandler。

### 5.2 推荐目录边界

建议使用以下 UI 目录：

```text
app/src/main/java/com/shakeguard/
  ui/
    ShakeGuardApp.kt
    navigation/
      AppRoutes.kt
      AppNavHost.kt
    home/
      HomeScreen.kt
      HomeViewModel.kt
    sources/
      SourcesViewModel.kt
      SourcePickerScreen.kt
      SourceDetailScreen.kt
    rules/
      RulesViewModel.kt
      RulesScreen.kt
      RuleEditorScreen.kt
    activity/
      ActivityLogViewModel.kt
      ActivityLogScreen.kt
      ActivityEventUiModel.kt
    system/
      AccessibilityStatusReader.kt
      NotificationCapabilityReader.kt
      AppCatalog.kt
```

目录是建议的实现边界，不要求每个文件都独立创建；但职责不得重新集中到 Activity 或 Composable 中。

### 5.3 深模块与接口边界

以下模块应提供较小、稳定的接口，把复杂行为隐藏在实现内部：

#### UI 状态模块

ViewModel 对页面提供一个不可变状态和一组用户意图。页面不需要了解数据库实体、线程切换或异常转换。

#### RuleRepository

Repository 隐藏 Room Entity 到领域模型和 UI 查询模型的转换，负责来源、规则、事件的读写语义。

#### FeedbackHandler

唯一公开入口为：

```kotlin
suspend fun handle(command: FeedbackCommand): FeedbackResult
```

它隐藏事件校验、一次性 token、事务写入、幂等规则处理和失败结果。

#### ProtectionGate

ProtectionGate 只负责回答当前全局保护是否启用，不负责记录事件、不负责通知、不负责解绑服务。

#### AppCatalog

AppCatalog 隐藏 PackageManager 查询、应用标签解析、图标解析、排序、缓存和卸载应用降级。

## 6. 导航与页面信息架构

### 6.1 路由

推荐路由：

```text
home
rules
activity
app-picker
source/{packageName}
rule-editor?ruleId={ruleId}
```

路由参数只使用：

- `packageName`。
- `ruleId`。
- 必要时使用 `eventId`。

不得把完整 Entity 或包含图标、Drawable、异常对象的复杂数据塞入导航参数。详情页面进入后通过 ViewModel 重新读取数据。

### 6.2 返回行为

- 子页面使用系统返回手势或顶部返回按钮回到上级页面。
- 未保存的编辑状态离开前需要确认。
- 保存成功后返回上级页面，并以 Room/DataStore Flow 更新列表。
- 清空对话框取消时返回 ActivityLog，不改变数据。

## 7. 页面设计与状态模型

### 7.1 首页 Home

首页展示：


- 全局保护开关。
- 无障碍服务状态。
- 已配置来源数量。
- 已暂停来源数量。
- 通知能力状态。
- 最近活动摘要。
- 添加来源和查看活动的入口。

首页状态至少包括：

```text
Loading
AccessibilityDisabled
ProtectionDisabled
NoSources
Active
Error
```

状态优先级固定为：

```text
Loading
  -> AccessibilityDisabled
  -> ProtectionDisabled
  -> NoSources
  -> Active
```

如果全局开关关闭且无障碍服务未授权，优先显示授权未完成，因为当前系统能力尚未建立；当服务已绑定但保护开关关闭时显示“保护已关闭，服务仍保持连接”。

首页操作：

- 切换全局保护开关：写入 DataStore，服务保持绑定。
- 点击无障碍授权入口：跳转系统设置。
- 点击添加来源：进入应用选择页。
- 点击通知引导：申请权限或跳转通知设置。
- 点击最近活动：进入 ActivityLog。

### 7.2 应用选择页 SourcePicker

应用选择页显示可启动的已安装应用，列表项包括：

- 应用图标。
- 应用名称。
- 必要时显示包名。
- 当前状态：未配置、已保护、已暂停。

交互：

- 支持按应用名称或包名搜索。
- 点击未配置应用创建来源。
- 点击已暂停来源进入详情，不删除原配置。
- 点击已保护来源进入详情。
- ShakeGuard 自身默认不作为来源选项。

应用 label 和 icon 运行时由 PackageManager 获取；持久化数据只保存 package name 及来源配置。

页面状态：

```text
Loading
Loaded
Empty
Error
```

应用已卸载但 Room 中仍有配置时，不应从来源管理页静默删除。来源列表以包名作为降级名称，并标记应用不可用，允许用户明确删除或保留。

### 7.3 来源详情页 SourceDetail

来源详情展示：

- 应用图标、名称和包名。
- “已保护”或“已暂停”状态。
- 保护启用开关。
- 每来源时间窗。
- 来源级阻止开关。
- 相关组合规则数量。
- 查看相关规则入口。

时间窗控件需要同时支持快速调整和精确输入。当前 DataStore 默认值为 5 秒，具体来源已保存的 `windowMs` 优先使用。保存值必须接受数据层的有效范围校验。

暂停来源仍可编辑时间窗和来源级阻止设置。恢复保护只改变 `enabled`，其他字段保持不变。

页面保存失败时保持用户编辑值，显示错误并允许重试；成功后返回来源列表。

### 7.4 规则页 Rules

规则页展示全部 source-target 规则，建议提供筛选：


- 全部。
- 允许。
- 阻止。
- 冲突。

每条规则展示：

- 来源应用。
- 目标应用。
- 规则类型。
- 启用状态。
- 规则来源，例如手动配置或反馈生成。
- 更新时间。
- 冲突提示。

同一 source-target 同时有启用或停用的 ALLOW 和 BLOCK 时，仍在全部列表显示两条规则；启用状态和冲突状态都必须透明展示。

规则操作：

- 新增规则。
- 修改规则类型或目标。
- 单独启用/停用。
- 单独删除。
- 进入来源详情。

新增或编辑规则时，用户依次选择来源、目标和类型。若同类型规则已存在，页面显示重复提示并阻止产生无意义的重复有效规则；另一类型规则存在时显示冲突提示，但允许保存。

### 7.5 规则编辑页 RuleEditor

规则编辑页状态：

```text
Loading
Editing
Saving
Saved
Error
```

新规则必须明确选择：

- 来源应用。
- 目标应用。
- 允许或阻止。

编辑已有规则时保留其 ID 和来源信息，不因为 UI 编辑而重新生成重复记录。保存时间使用统一的 epoch clock。

### 7.6 ActivityLog

ActivityLog 默认观察最近 100 条事件，按 `createdAt DESC` 展示。列表项包含：

- 来源应用到目标应用。
- 决策：阻止、放行、观察或忽略。
- 动作：已返回、返回失败或未执行。
- 时间。
- 用户反馈状态。

展开详情后展示：

- 事件 ID。
- source package。
- target package。
- elapsed time。
- decision。
- matched rule ID。
- action result。
- user feedback。
- createdAt。

页面状态：

```text
Loading
Loaded
Empty
Refreshing
ErrorWithPreviousData
```

无事件时显示空状态，说明清空后来源和规则仍然保留，并提供返回规则或来源管理的入口。

#### 反馈按钮

只有具备有效事件 ID 且决策为 BLOCK 的事件显示四种反馈。对于 ALLOW、IGNORE、OBSERVE 或没有事件 ID 的记录，不显示这些反馈按钮。

执行反馈后：

- `Applied`：显示成功提示，重新读取受影响状态。
- `NotFound`：提示事件已不存在，刷新列表。
- `Failed`：显示失败原因，不能显示成功状态。

如果事件已有反馈，显示当前反馈标签。再次选择不同反馈必须经过确认，避免用户误触修改持久化规则或来源状态。

#### 清空记录

清空入口必须显示二次确认对话框，确认文案明确指出：

```text
将删除全部跳转记录。
来源配置、时间窗和规则不会受到影响。
此操作不可恢复。
```

确认后调用 Repository 的全量事件删除接口。按钮进入操作中状态，防止重复提交。成功后等待事件 Flow 变为空列表，再展示空状态；失败时保留现有列表并显示错误。

## 8. 数据接口与数据流

### 8.1 Room 数据职责

现有三张表的 UI 相关语义为：

```text
protected_sources
  来源包名、启用状态、时间窗、来源级阻止、创建/更新时间

pair_rules
  来源包名、目标包名、ALLOW/BLOCK、启用状态、来源、更新时间

jump_events
  来源包名、目标包名、耗时、决策、规则 ID、返回结果、用户反馈、创建时间
```

管理 UI 与服务使用不同观察接口：

- 服务使用启用来源观察接口。
- 管理页面使用全部来源观察接口。
- ActivityLog 使用最近 100 条事件观察接口。

### 8.2 Phase 0 必须提供的接口

Phase 0 是 UI 的必要后端配套，不是可选整理。至少需要提供：

```text
ProtectedSourceDao.observeAll()
PairRuleDao.observeAll()
PairRuleDao.setEnabled(id, enabled, updatedAt)
PairRuleDao.delete(id)
JumpEventDao.observeRecent(limit = 100)
JumpEventDao.deleteAll()
RuleRepository.observeAllSources()
RuleRepository.observeAllRules()
RuleRepository.observeRecentEvents(limit = 100)
RuleRepository.clearAllEvents()
```

接口的行为约束：

- `observeAllSources()` 必须包含启用和暂停来源。
- `clearAllEvents()` 只能删除 `jump_events`。
- `clearAllEvents()` 不得修改 `protected_sources` 或 `pair_rules`。
- `setEnabled` 只更新目标规则的启用状态和更新时间。
- 删除规则必须按规则 ID 精确操作。

具体命名可以与现有代码风格一致，但语义和能力必须完整。

### 8.3 DataStore 职责

DataStore 保存进程重启后仍需保留的全局偏好：

- 全局保护开关。
- 默认时间窗。
- 通知偏好。

来源的具体时间窗属于来源配置，应保存在 Room，而不是只保存为全局偏好。

`SettingsStore` 应加入 Application 依赖图，由 Activity 和 ViewModel 共享同一实例。

### 8.4 Compose 数据流

标准数据流：

```text
Room DAO Flow / DataStore Flow
        -> Repository
        -> ViewModel StateFlow
        -> collectAsStateWithLifecycle
        -> Compose Screen
```

用户操作流：

```text
Compose 用户事件
        -> ViewModel intent
        -> Repository / FeedbackHandler / SettingsStore
        -> Room 或 DataStore
        -> Flow 推送新状态
        -> Compose 重组
```

成功状态以数据流为准，不在点击回调中维护第二份长期缓存。Snackbar、Dialog 导航和一次性错误使用独立的 UI effect 流或事件通道。

### 8.5 页面 ViewModel

推荐至少拆分：

- `HomeViewModel`：首页保护状态、授权状态、通知能力和摘要。
- `SourcesViewModel`：应用目录、来源列表、来源详情和时间窗。
- `RulesViewModel`：规则列表、筛选、冲突、启停、编辑和删除。
- `ActivityLogViewModel`：事件列表、反馈、清空和一次性 UI effect。

ViewModel 对外只暴露不可变状态。Android Context、DAO 和可变实体不直接暴露给 Composable。

## 9. ProtectionGate 运行时契约

### 9.1 责任

ProtectionGate 是保护服务和 SettingsStore 之间的窄接口。它只提供当前全局保护是否启用，并将 DataStore 的异步变化转换为服务可以及时读取的状态。

它不负责：

- 解绑或绑定无障碍服务。
- 创建保护事件。
- 执行 `Back`。
- 发布通知。
- 清除 token。
- 修改来源或规则。

### 9.2 处理顺序

前台事件进入无障碍服务后，处理顺序必须满足：

```text
前台窗口事件
  -> 检查 ProtectionGate
  -> Gate 关闭：直接放行并结束
  -> Gate 开启：进入现有 SessionTracker/RuleEvaluator/Coordinator
  -> BLOCK 时执行原有记录和首次通知逻辑
```

Gate 关闭路径不能先调用完整 Coordinator 再丢弃结果，因为那可能改变会话状态、执行副作用或产生不可见记录。

### 9.3 验收行为

Gate 关闭时必须验证：

- `Back` 调用次数为零。
- 保护事件 recorder 调用次数为零。
- 首次阻断通知调用次数为零。
- 新跳转不产生 BLOCK 事件。
- 既有事件仍可在 ActivityLog 查看，直到用户清空。

Gate 重新开启后，下一次符合条件的跳转即可执行正常保护流程。

## 10. 权限、系统状态与错误处理

### 10.1 无障碍服务

无障碍服务授权不是普通 runtime permission。UI 必须：

- 查询当前服务是否在启用列表。
- 通过 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 打开系统设置。
- 从系统设置返回 Activity 后在 `onResume` 或等价生命周期点重新检测。
- 跳转系统设置失败时显示明确错误。
- 服务未启用时显示“保护未生效”，不显示“保护中”。

无障碍服务只使用事件中的包名和 Activity 类名，不读取窗口正文、输入内容、通知正文或 URL。

### 10.2 通知权限

通知是可选能力。UI 至少区分：

- Android 12 及以下无需 `POST_NOTIFICATIONS` runtime permission 的情况。
- Android 13 及以上权限已授予。
- Android 13 及以上权限被拒绝。
- 系统通知总开关关闭。
- 通知 channel 被关闭。
- 查询或发布时发生系统异常。

通知不可用时：

- 首页显示“通知提醒不可用”或等价降级状态。
- 允许用户跳转系统通知设置。
- ActivityLog 仍然可见。
- 四种反馈仍然可用。
- 保护决策、事件记录和无障碍服务不受影响。

### 10.3 Room 和 DataStore 错误

读取失败：显示错误状态；若有上一份有效数据，保留上一份数据并显示刷新失败提示。
保存失败：保留编辑状态，显示错误，允许重试。
反馈失败：显示失败结果，不标记成功，不隐藏原事件。
清空失败：保留当前列表，不修改来源和规则状态，允许重试。

### 10.4 应用信息解析失败

PackageManager 找不到应用时：

- 使用包名作为名称后备。
- 使用默认图标。
- 保留 Room 中的来源和规则。
- 标记“应用已卸载或不可用”。
- 允许用户明确删除来源或规则。

## 11. 本地隐私边界

### 11.1 保存内容

本地 Room 只保存：

- source package。
- target package。
- 时间窗和规则配置。
- 决策、返回结果、耗时。
- 用户反馈。
- 创建时间和规则 ID。

DataStore 只保存全局开关、默认时间窗和通知偏好。

### 11.2 不保存和不读取的内容

本阶段不读取或保存：

- 窗口正文。
- 输入框内容。
- 通知正文。
- 浏览器完整 URL。
- 网络请求内容。
- 用户身份信息。
- 云端账户信息。

应用名称和图标仅通过 PackageManager 运行时解析，不作为规则主键或持久化身份。

### 11.3 权限边界

首版不申请网络权限。无障碍服务的用途必须在产品文案和发布材料中明确说明。通知权限只用于保护反馈通知，不用于上传数据。

## 12. 性能与可用性

### 12.1 应用目录

- PackageManager 查询在 IO 调度器执行。
- ViewModel 缓存应用展示信息，避免每次重组重复解析。
- LazyColumn 使用稳定 package name 作为 key。
- 搜索在已加载内存列表中执行。
- 图标解析失败使用默认图标。

### 12.2 ActivityLog

- 默认最多查询最近 100 条。
- 使用 LazyColumn 展示。
- 列表行不重复访问 PackageManager。
- 暂不引入分页库；只有在产品明确需要完整历史浏览时再扩展分页。
- 清空操作期间禁用重复点击。

### 12.3 Flow 与生命周期

ViewModel 使用 `stateIn` 和 `SharingStarted.WhileSubscribed` 管理共享状态。Composable 使用生命周期感知的状态收集。页面退出后不应继续创建新的长期观察者。

### 12.4 操作反馈

所有写操作都需要：

- 操作中状态。
- 防重复提交。
- 成功或失败的一次性反馈。
- 由 Room/DataStore Flow 驱动最终显示状态。

## 13. 测试规格

### 13.1 Room 和 Repository

必须验证：

- `observeAllSources` 同时返回启用和暂停来源。
- 暂停来源保留 windowMs 和 sourceLevelBlock。
- 暂停来源的 pair rules 仍存在。
- 恢复来源只改变 enabled。
- 最近事件接口默认使用 100 条限制。
- `clearAllEvents` 删除全部事件。
- 清空后事件 Flow 为空。
- 清空后 protected_sources 仍存在且字段不变。
- 清空后 pair_rules 仍存在且字段不变。
- 同一 source-target 可以同时存在 ALLOW 和 BLOCK。
- ALLOW 和 BLOCK 可以分别启用、停用和删除。

### 13.2 ProtectionGate 和服务

必须验证：

- Gate 关闭时不执行 Back。
- Gate 关闭时不创建 BLOCK 事件。
- Gate 关闭时不发送首次阻断通知。
- Gate 关闭时服务仍保持绑定语义。
- Gate 重新打开后下一个新跳转恢复保护。
- 历史事件不因关闭开关被删除。

### 13.3 ViewModel

必须覆盖：

- 首页 Loading、无障碍未授权、全局关闭、无来源和 Active 状态。
- 返回系统设置后授权状态刷新。
- 通知拒绝不改变保护状态。
- 来源添加、暂停、恢复和时间窗保存。
- 规则筛选和 ALLOW/BLOCK 冲突展示。
- 规则分别停用、编辑和删除。
- ActivityLog 默认 100 条、加载失败、清空成功和清空失败。
- 四种 FeedbackCommand 映射及 Applied、NotFound、Failed 结果。

### 13.4 Compose UI

必须验证：

- 三项底部导航可以切换。
- 全局保护开关关闭后显示服务仍连接的状态。
- 已暂停来源仍出现在来源列表。
- 已暂停来源可以恢复且配置不丢失。
- 应用选择页显示名称、图标和状态。
- 规则冲突提示可见。
- ALLOW 和 BLOCK 操作互不影响。
- BLOCK ActivityLog 显示四种反馈。
- 非 BLOCK 事件不显示四种反馈。
- 清空前出现确认对话框。
- 取消清空不改变列表。
- 清空成功后显示空状态和保留配置说明。

### 13.5 权限和真实设备

至少在 Android 13 及以上验证：

- 通知权限允许。
- 通知权限拒绝。
- 系统通知总开关关闭。
- 无障碍服务启用和停用后的返回刷新。
- 全局保护开关关闭和立即恢复。
- 通知不可用时 ActivityLog 反馈仍可执行。

## 14. Phase 0-5 实现边界

### Phase 0：UI 必要后端配套与运行时契约

范围：

- DAO 全量来源观察。
- DAO 全量规则观察、单条启停和删除。
- DAO 最近事件观察和全量清空。
- Repository 暴露 UI 所需的稳定接口。
- SettingsStore 纳入 Application 依赖图。
- 引入并接入 ProtectionGate。
- 补充 Room、Repository、ProtectionGate 和服务行为测试。

不包含：Compose 页面、导航视觉实现和应用选择器。

完成条件：UI 不需要直接依赖 DAO，清空事件不会改变来源和规则，关闭保护不会产生保护副作用。

### Phase 1：首页与授权状态

范围：

- 单 Activity Compose 入口。
- 三项底部导航骨架。
- 首页状态模型和 ViewModel。
- 无障碍状态检测、系统设置跳转和返回刷新。
- 全局保护开关。
- 通知可选引导和状态展示。

完成条件：用户能判断保护是否生效，能完成无障碍授权，能关闭和重新打开保护而不解绑服务。

### Phase 2：来源应用与时间窗

范围：

- PackageManager 应用目录适配器。
- 应用选择页。
- 来源列表和来源详情。
- 已暂停来源展示和恢复。
- 每来源时间窗。
- 来源级阻止设置。

完成条件：用户能通过应用名称添加来源、暂停来源、恢复来源，并确认暂停期间配置和规则仍存在。

### Phase 3：来源和组合规则管理

范围：

- 全量规则列表。
- ALLOW/BLOCK 筛选。
- 冲突展示。
- 规则新增、编辑、启停和删除。
- 规则来源和更新时间展示。

完成条件：同一 source-target 的 ALLOW 和 BLOCK 可以同时保存并分别操作，UI 不改变引擎的实际优先级。

### Phase 4：ActivityLog 与反馈

范围：

- 最近 100 条事件列表。
- 事件详情。
- 四种反馈。
- FeedbackHandler 结果映射。
- 清空确认、全量清空和空状态。

完成条件：反馈与通知使用同一 FeedbackHandler，清空只删除事件，清空后来源和规则仍可用。

### Phase 5：整体验证与性能收尾

范围：

- Compose UI 测试和 ViewModel 测试补齐。
- Room migration 和真实数据库验证。
- Android 13+ 权限验证。
- 无障碍服务启停和全局开关验证。
- 应用卸载后的包名降级。
- 大量事件列表滚动和应用列表加载检查。

完成条件：所有本规格中的产品契约、数据保留规则、错误降级和隐私边界都有可重复的测试证据。

## 15. 文件范围

### Phase 0 可能修改或新增

- `app/src/main/java/com/shakeguard/data/Daos.kt`
- `app/src/main/java/com/shakeguard/data/Repositories.kt`
- `app/src/main/java/com/shakeguard/data/SettingsStore.kt`
- `app/src/main/java/com/shakeguard/ShakeGuardApplication.kt`
- `app/src/main/java/com/shakeguard/protection/ProtectionGate.kt`
- 对应 `app/src/test` 和 `app/src/androidTest` 数据与服务测试。

### Phase 1 可能修改或新增

- `app/src/main/java/com/shakeguard/MainActivity.kt`
- `app/src/main/java/com/shakeguard/ui/ShakeGuardApp.kt`
- `app/src/main/java/com/shakeguard/ui/navigation/AppRoutes.kt`
- `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- `app/src/main/java/com/shakeguard/ui/home/HomeScreen.kt`
- `app/src/main/java/com/shakeguard/ui/home/HomeViewModel.kt`
- `app/src/main/java/com/shakeguard/ui/system/AccessibilityStatusReader.kt`
- `app/src/main/java/com/shakeguard/ui/system/NotificationCapabilityReader.kt`

### Phase 2 可能新增

- `app/src/main/java/com/shakeguard/ui/system/AppCatalog.kt`
- `app/src/main/java/com/shakeguard/ui/sources/SourcesViewModel.kt`
- `app/src/main/java/com/shakeguard/ui/sources/SourcePickerScreen.kt`
- `app/src/main/java/com/shakeguard/ui/sources/SourceDetailScreen.kt`

### Phase 3 可能新增

- `app/src/main/java/com/shakeguard/ui/rules/RulesViewModel.kt`
- `app/src/main/java/com/shakeguard/ui/rules/RulesScreen.kt`
- `app/src/main/java/com/shakeguard/ui/rules/RuleEditorScreen.kt`

### Phase 4 可能新增

- `app/src/main/java/com/shakeguard/ui/activity/ActivityLogViewModel.kt`
- `app/src/main/java/com/shakeguard/ui/activity/ActivityLogScreen.kt`
- `app/src/main/java/com/shakeguard/ui/activity/ActivityEventUiModel.kt`

README、旧计划文件和与本规格无关的业务代码不属于本次规格落地提交。

## 16. 验收矩阵

| 编号 | 验收条件 | 结果要求 |
| --- | --- | --- |
| A1 | 全局保护关闭 | 服务保持绑定，新跳转放行，不 Back、不记录 BLOCK、不发首次通知 |
| A2 | 全局保护重新开启 | 下一次新跳转恢复保护 |
| A3 | 主导航 | 只有首页、规则、活动三项底部导航 |
| A4 | 暂停来源 | 来源仍可见，状态为已暂停，配置和规则保留 |
| A5 | 恢复来源 | 只恢复 enabled，不重置其他字段 |
| A6 | 规则冲突 | 同一 source-target 的 ALLOW/BLOCK 同时存在时明确显示冲突 |
| A7 | 规则独立操作 | ALLOW/BLOCK 可分别启停、编辑、删除 |
| A8 | ActivityLog 数量 | 默认最近 100 条，按时间倒序 |
| A9 | 清空记录 | 二次确认后只删除全部 jump_events |
| A10 | 清空后状态 | 显示空状态，来源、时间窗和规则仍存在 |
| A11 | 反馈入口 | BLOCK 事件使用四种反馈，统一经过 FeedbackHandler |
| A12 | 通知拒绝 | 通知不可用不影响保护、记录和 ActivityLog 反馈 |
| A13 | 隐私边界 | 不读取窗口正文、输入内容、通知正文、URL 或网络内容 |
| A14 | 应用标识 | 持久化使用包名，名称和图标运行时解析 |

## 17. 设计自检结论

- 产品契约覆盖全局开关、三项导航、暂停来源、规则冲突、ActivityLog 和四种反馈。
- Phase 0 明确作为 UI 必要后端配套，并覆盖全量来源、规则管理、事件清空和 ProtectionGate。
- 全局保护关闭路径没有与无障碍未授权路径混淆。
- 清空事件与来源、时间窗、规则的保留关系明确且可测试。
- ALLOW/BLOCK 冲突展示与保护引擎优先级职责分离，没有在 UI 层复制决策逻辑。
- 通知权限明确为可选能力，不会改变核心保护结果。
- 普通用户文案、Android-only 平台边界和本地隐私边界已明确。
- 性能风险、错误降级、生命周期和测试范围均已覆盖。
- 本文范围聚焦可操作原型 UI 及其必要后端配套，不包含代码实现、README 更新或发布流程扩展。
