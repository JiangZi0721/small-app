# ShakeGuard 通知与反馈闭环设计

日期：2026-08-10  
状态：用户已确认交互语义，等待规格复核

## 1. 目标

在一次跳转被 ShakeGuard 返回后，为用户提供可恢复、可解释的本地反馈操作。通知和 ActivityLog 必须调用同一个反馈处理器，确保在用户拒绝通知权限时仍能完成全部操作，并避免两套入口产生不同规则。

本阶段包含四种操作：

- `ALLOW_ONCE`，界面文案为“允许本次”；
- `ALLOW_PAIR`，界面文案为“始终允许此跳转”；
- `CONFIRM_AD`，界面文案为“这是广告”；
- `STOP_PROTECTING_SOURCE`，界面文案为“停止保护此应用”。

## 2. “允许本次”的精确定义

ShakeGuard 在无 root 环境中只能在跳转发生后执行返回，无法可靠保存并重新发送其他应用的原始 `Intent`。因此点击“允许本次”不会自动重新打开目标页面，而是创建一个内存中的一次性允许令牌。

令牌以 `(sourcePackage, targetPackage)` 为键，具有以下约束：

- 创建后 30 秒内有效；
- 只允许下一次完全相同的来源-目标跳转；
- 命中后立即消费，后续跳转重新进入正常规则评估；
- 超过 30 秒未命中则自动失效；
- 不允许其他来源或其他目标复用；
- 不写入 Room，应用进程重启后自动丢失；
- 一次性允许的优先级高于来源级阻止和组合阻止规则；
- 消费发生在规则评估之前，确保被允许的那一次不会再次执行系统返回。

用户操作流程为：点击“允许本次” → 返回来源应用 → 在 30 秒内再次触发登录、支付或分享入口 → ShakeGuard 消费令牌并放行。

## 3. 持久化反馈

### 始终允许此跳转

为精确的来源-目标组合写入启用的 `ALLOW` 规则，`origin=FEEDBACK`。重复操作必须幂等：已有相同允许规则时更新它，不创建无限重复记录。该规则继续受来源应用时间窗约束，时间窗外所有规则仍不参与评估。

### 这是广告

为精确的来源-目标组合写入启用的 `BLOCK` 规则，`origin=FEEDBACK`，并把对应跳转事件的 `userFeedback` 更新为 `AD`。如果来源级阻止已开启，组合规则仍用于记录用户确认和未来可解释性。重复确认必须幂等。

### 停止保护此应用

将来源配置的 `enabled` 设置为 `false`，保留时间窗、创建时间和其他规则，便于用户以后重新启用。该操作不删除历史事件和组合规则。

## 4. 模块边界

### FeedbackHandler

纯 Kotlin 协调器，公共入口为：

```kotlin
suspend fun handle(command: FeedbackCommand): FeedbackResult
```

它负责校验来源/目标、创建一次性令牌或调用 Repository 持久化反馈。通知接收器和 ActivityLog ViewModel 都只能通过这个入口修改反馈状态。

### OneTimeAllowanceStore

进程内存储，使用注入的 `MonotonicClock` 判断 30 秒有效期。公共行为包括创建令牌和原子消费令牌。令牌消费不依赖墙上时钟，避免用户修改系统时间影响有效期。

### ProtectionCoordinator

在调用 `RuleEvaluator` 前尝试消费精确来源-目标令牌。消费成功时返回 `ALLOW`，不调用 `GLOBAL_ACTION_BACK`，也不把这次放行记录为新的拦截。

### RuleRepository / DAO

增加组合规则幂等 upsert、事件反馈更新和来源禁用能力。Room 操作保持本地，不新增联网权限。

### Notification

首次 `BLOCK` 的来源-目标组合可发布通知。通知显示来源和目标应用标签，Action 只携带事件 ID 或稳定包名，不携带窗口正文。Android 13+ 未授予 `POST_NOTIFICATIONS` 时跳过通知，但不影响保护、事件记录或 ActivityLog 反馈。

## 5. 数据流

```text
BLOCK 结果
  -> 保存 JumpEvent
  -> 通知权限可用且该组合首次通知
  -> 发布反馈通知

用户点击反馈 Action / ActivityLog 操作
  -> FeedbackCommand
  -> FeedbackHandler
  -> 一次性内存令牌 或 Room 持久化更新
  -> FeedbackResult

下一次来源-目标跳转
  -> ProtectionCoordinator
  -> 优先消费一次性令牌
  -> 未消费时执行正常 RuleEvaluator
```

## 6. 错误处理与安全边界

- 找不到事件或来源配置时返回明确失败，不创建部分规则；
- 数据库失败时不假装反馈成功；
- 重复广播和重复点击必须幂等；
- PendingIntent 使用不可变标志，并为不同事件/动作提供稳定唯一标识；
- 广播接收器不导出，只接收本应用创建的显式 PendingIntent；
- 通知权限拒绝不是保护失败；
- 不读取窗口正文、输入框、通知正文或网络内容；
- 本阶段不自动启动第三方目标应用。

## 7. 测试边界

JVM 单元测试：

- 一次性令牌在 30 秒内精确匹配并只消费一次；
- 30 秒边界有效，超过边界失效；
- 不同来源或目标无法消费；
- 单调时钟回拨不会扩大令牌寿命；
- `ALLOW_PAIR`、`CONFIRM_AD`、`STOP_PROTECTING_SOURCE` 的幂等行为；
- 一次性允许优先于来源级和组合阻止；
- 返回失败和 Repository 异常可观察。

Android instrumentation 测试：

- Room 组合规则 upsert、事件反馈更新和来源禁用；
- 通知渠道和 Action PendingIntent；
- 通知权限不可用时保护流程继续工作；
- 广播接收器重复接收同一 Action 不产生重复规则。

## 8. 不在本阶段实现

- 云端黑名单、反馈上传或机器学习训练；
- 自动重放原始 `Intent`；
- 读取浏览器完整 URL；
- 完整 ActivityLog Compose 页面布局；
- 跨应用来源/目标测试 APK。
