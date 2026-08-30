# ShakeGuard 设计规格

日期：2026-08-09  
状态：已获用户确认，等待实现计划  
目标：课程级、可安装、无 root 的 Android 摇一摇广告跳转拦截原型

## 1. 背景与目标

部分应用在开屏广告中读取陀螺仪或加速度计，检测到手机轻微角度变化后打开浏览器、应用商店或其他广告落地页。普通 Android 应用无法修改广告应用的传感器输入，也没有全局 Intent 拦截能力，因此本项目采用“跳转后快速恢复”的可行目标。

成功标准：明确命中规则的跳转在测试设备上至少 95% 能在 1 秒内返回；时间窗外 100% 不执行拦截；每次拦截均可解释、可撤销、可追溯。

## 2. 范围

### 包含

- Android 10+；
- Kotlin + Jetpack Compose；
- 一个必需的 AccessibilityService；
- 来源应用保护名单和每应用时间窗；
- 来源级阻止规则；
- 来源应用 + 目标应用组合阻止/允许规则；
- 本地 Room 事件记录和 DataStore 设置；
- 首次拦截通知与用户反馈；
- 离线导入/导出规则（实现阶段可作为后续小功能）。

### 不包含

- root、Magisk、LSPosed、Xposed；
- 本地 VPN、DNS 过滤或 HTTPS 解密；
- 云端黑名单、用户行为上传或机器学习训练；
- 读取页面正文、输入框、通知内容；
- 保证跳转前阻止或获取完整浏览器 URL。

## 3. 用户体验

首页采用组合布局：保护总状态、受保护来源应用、最近拦截和规则入口同时可见。

首次使用流程：解释数据最小化原则 → 打开系统无障碍设置 → 返回后选择来源应用 → 为每个应用设置时间窗 → 可选开启通知。

规则创建采用双入口：用户可以从已安装应用列表添加来源，也可以从活动记录确认真实跳转并生成精确的来源-目标组合规则。

## 4. 规则模型

每个来源应用具有 `enabled` 和 `windowMs`。应用进入前台时记录 `sessionStartedAt = elapsedRealtime()`。只有 `elapsedRealtime() - sessionStartedAt <= windowMs` 时才评估拦截规则。

决策顺序：

1. 系统界面、输入法、启动器、ShakeGuard 自身和同包 Activity 切换忽略；
2. 没有来源配置或已经超出时间窗，放行且不产生规则命中记录；
3. 临时放行或长期允许组合规则，放行；
4. 来源级阻止规则命中，自动返回；
5. 来源 + 目标组合阻止规则命中，自动返回；
6. 无规则命中，放行，可生成观察记录。

允许例外必须高于来源级阻止，否则用户无法恢复正常登录、支付和分享。

## 5. 架构

### UI 层

Compose 页面：Home、ProtectedApps、Rules、ActivityLog、PermissionGuide、Settings。UI 通过 ViewModel 暴露不可变状态，不直接调用无障碍 API。

### Accessibility 层

`ShakeGuardAccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED` 和必要的窗口变化事件。服务配置 `canRetrieveWindowContent=false`，只使用事件中的包名和类名，避免读取页面内容。事件交给 `ProtectionSessionTracker`。

### Protection 层

- `ProtectionSessionTracker`：追踪来源应用前台会话、单调时间和防循环恢复状态；
- `RuleEvaluator`：纯函数式评估规则，输入事件、时间、配置，输出 `Allow`、`Block`、`Ignore` 和 `Observe`；
- `BackActionExecutor`：执行一次 `GLOBAL_ACTION_BACK`，记录成功或失败；
- `EventDeduplicator`：合并短时间内重复窗口事件。

### Data 层

Room 保存来源配置、组合规则和事件；DataStore 保存全局开关、默认时间窗和通知偏好。Repository 隔离 DAO，便于使用内存实现做单元测试。

### Notification 层

首次拦截可发通知，动作包括“仍然打开”“以后允许此组合”“只允许一次”“这是广告”。Android 13+ 通知权限被拒绝时，所有反馈仍可在活动记录中完成。

## 6. 数据结构草案

```text
ProtectedSource
  id, packageName, enabled, windowMs, createdAt, updatedAt

PairRule
  id, sourcePackage, targetPackage, kind(BLOCK|ALLOW), enabled,
  origin(MANUAL|FEEDBACK), createdAt, updatedAt

JumpEvent
  id, sourcePackage, targetPackage, optionalTargetUrl,
  sessionElapsedMs, decision, matchedRuleId, actionResult,
  userFeedback, createdAt
```

包名是规则主键，应用名称和图标运行时通过 `PackageManager` 解析，避免应用改名导致数据失效。

## 7. 错误处理与隐私

服务未开启或被厂商停止时显示明确的降级状态，不假装保护成功。返回失败只尝试一次，防止返回循环。目标未知时只记录，不自动返回。规则数据库启动时校验，异常时进入只读安全模式。

所有数据默认本地保存，事件保留 30 天并可清空。首版不申请联网权限，因此不会上传应用清单、包名事件或用户反馈。公开发布前必须补充无障碍 API 用途披露、隐私政策、数据安全表单，并验证应用商店审核要求。

## 8. 测试计划

- RuleEvaluator：规则优先级、时间窗边界、允许例外、系统豁免；
- SessionTracker：来源启动、返回恢复、重复事件和时间回拨；
- Room：迁移、卸载规则、事件保留和清理；
- Accessibility 集成：测试来源 APK → 测试目标 APK → 自动返回；
- 通知反馈：首次触发、通知拒绝、四种反馈动作；
- 真机矩阵：Android 10/12/14/16，原生 Android 及主要厂商系统；
- 回归：横竖屏切换、目标应用卸载、服务撤销、数据库异常。

## 9. 风险与后续

最大风险是厂商对无障碍服务的后台限制和目标页面闪现。后续可以增加 OEM 诊断、可选本地 VPN 域名过滤和规则导入导出，但不能把这些能力描述成无 root 的系统级 Intent 防火墙。
