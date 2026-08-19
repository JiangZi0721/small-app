# ShakeGuard 可操作原型 UI 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不扩大隐私权限边界的前提下，为 Android 29+ 的 ShakeGuard 构建可操作的 Compose 原型：三项主导航、来源/规则管理、最近 100 条 ActivityLog、四种反馈，以及服务保持绑定时可即时开关的全局保护。

**Architecture:** 保留单 Activity，在 `ShakeGuardApplication` 的进程级依赖图中共享 Room、DataStore、FeedbackHandler 和新增的 ProtectionGate。Composable 只渲染不可变状态并把事件发给页面级 ViewModel；Repository 隔离 DAO，FeedbackHandler 仍是所有事件反馈的唯一写入入口。无障碍服务先读取 ProtectionGate；未就绪或关闭时清除保护会话、直接放行，不执行 Back、不写 BLOCK 事件、不发送通知。

**Tech Stack:** Kotlin 2.1、Jetpack Compose Material 3、Navigation Compose、Lifecycle ViewModel/StateFlow、Room 2.7、DataStore Preferences、Kotlin Coroutines、AndroidX Activity Result、JUnit 4、AndroidX instrumentation、Compose UI Test。

**Design source:** `docs/superpowers/specs/2026-08-12-operable-prototype-ui-design.md`，批准提交 `225301deca9d6fe095c6b0bed43b04cea5626ad8`。

---

## 0. 执行前约束与验证基线

本计划中的每项任务都在 `F:/Codex-app/ShakeGuard/.worktrees/feedback-notifications` 执行。开始任何任务前先运行：

```powershell
$env:JAVA_HOME = "G:\Java7\JDK17\jdk-17.0.20.8-hotspot"
$env:ANDROID_SDK_ROOT = "F:\Andriod-Studio\Sdk"
$env:GRADLE_USER_HOME = "C:\Users\zero\AppData\Local\Temp\shakeguard-task4-gradle-home"
$env:ANDROID_USER_HOME = "F:\Codex-app\shakeguard-ui-android-home"
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --no-daemon --console=plain
```

预期：基线 JVM suite 成功。构建不会向项目目录创建 Android 用户目录。`ANDROID_USER_HOME` 使用仓库外临时目录，避免已知的 `debug.keystore.lock` 问题。

每一项提交前运行：

```powershell
git diff --check
git status --short
```

只暂存该任务列出的文件；不把无关修改、生成物或其他用户的未暂存文件加入提交。不要修改或暂存 `docs/superpowers/plans/2026-08-10-feedback-notification.md`。

## 1. 文件地图与模块责任

### 1.1 Phase 0：UI 必要后端配套

- 修改 `app/build.gradle.kts` 与 `gradle/libs.versions.toml`：为 ViewModel、StateFlow 和 Compose UI 测试补齐显式依赖。
- 修改 `app/src/main/java/com/shakeguard/data/Daos.kt`：为来源全量观察、规则全量观察/启停/删除、事件清空提供 SQL 接口。
- 修改 `app/src/main/java/com/shakeguard/data/Repositories.kt`：将 Room entity 转换为稳定的 UI 查询模型，并提供来源、规则和事件管理入口。
- 新建 `app/src/main/java/com/shakeguard/protection/ProtectionGate.kt`：将 DataStore 全局开关转为进程级、fail-closed 的 Gate 状态。
- 修改 `app/src/main/java/com/shakeguard/ShakeGuardApplication.kt`：先共享 `SettingsStore`、ProtectionGate 和 UI 所需 Repository。
- 修改 `app/src/main/java/com/shakeguard/accessibility/ShakeGuardAccessibilityService.kt`：在 Application graph 已具备 Gate 后接入服务；Gate 未就绪或关闭时清除保护会话，不做保护副作用。
- 修改现有 data/protection/accessibility JVM 与 instrumentation 测试：使用真实 SQL 或 fake DAO 固化上述契约。

### 1.2 Phase 1：首页与导航外壳

- 修改 `app/src/main/java/com/shakeguard/MainActivity.kt`：从静态 Column 切换到 `ShakeGuardApp`。
- 新建 `app/src/main/java/com/shakeguard/ui/ShakeGuardApp.kt`：MaterialTheme、根 Scaffold 和导航宿主。
- 新建 `app/src/main/java/com/shakeguard/ui/navigation/AppRoutes.kt`：常量路由和参数构造函数。
- 新建 `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`：首页、规则、活动及子页面路由。
- 新建 `app/src/main/java/com/shakeguard/ui/home/HomeViewModel.kt` 与 `HomeScreen.kt`：首页状态、授权入口和开关。
- 新建 `app/src/main/java/com/shakeguard/ui/system/AccessibilityStatusReader.kt`、`NotificationCapabilityReader.kt`：系统状态适配器。
- 新建对应 JVM 和 Compose UI 测试。

### 1.3 Phase 2：来源应用与详情

- 新建 `app/src/main/java/com/shakeguard/ui/system/AppCatalog.kt`：PackageManager 查询、标签/图标解析、排序和卸载降级。
- 新建 `app/src/main/java/com/shakeguard/ui/sources/SourcesViewModel.kt`、`SourcePickerScreen.kt`、`SourceDetailScreen.kt`：来源选择、暂停/恢复、时间窗、来源级阻止设置。
- 新建对应 JVM 和 Compose UI 测试。

### 1.4 Phase 3：规则管理

- 新建 `app/src/main/java/com/shakeguard/ui/rules/RulesViewModel.kt`、`RulesScreen.kt`、`RuleEditorScreen.kt`：规则筛选、冲突显示、编辑、启停与删除。
- 新建对应 JVM 和 Compose UI 测试。

### 1.5 Phase 4：ActivityLog 与反馈

- 新建 `app/src/main/java/com/shakeguard/ui/activity/ActivityEventUiModel.kt`、`ActivityLogViewModel.kt`、`ActivityLogScreen.kt`：最近 100 条、事件详情、四种反馈和全量清空。
- 新建对应 JVM 和 Compose UI 测试。

### 1.6 Phase 5：集成、真实设备与学习文档

- 修改 `README.md`：说明 UI、权限、隐私、运行限制、验证结果和问题处理。
- 修改或新建 `app/src/androidTest/java/com/shakeguard/ui/OperablePrototypeUiInstrumentedTest.kt`：在设备上验证导航、授权降级和清空后的界面。
- 只在实际执行、结果可重复时更新 README 中的测试矩阵；不得声称未执行的真机验证已通过。

## 2. 统一类型和接口契约

后续任务必须使用以下名称，避免跨任务出现同义但不兼容的类型。

```kotlin
data class ManagedSource(
    val packageName: String,
    val enabled: Boolean,
    val windowMs: Long,
    val sourceLevelBlock: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ManagedPairRule(
    val id: Long,
    val sourcePackage: String,
    val targetPackage: String,
    val kind: RuleKind,
    val enabled: Boolean,
    val origin: String,
    val updatedAt: Long,
)

data class ActivityEvent(
    val id: Long,
    val sourcePackage: String,
    val targetPackage: String,
    val elapsedMs: Long,
    val decision: DecisionKind,
    val matchedRuleId: Long?,
    val actionResult: ActionResult,
    val userFeedback: String?,
    val createdAt: Long,
)

enum class ProtectionGateState { NotReady, Enabled, Disabled }

interface ProtectionGate {
    val state: StateFlow<ProtectionGateState>
}
```

`ManagedSource`、`ManagedPairRule` 和 `ActivityEvent` 是 Repository 公开给 UI 的不可变数据模型。现有 `ProtectedSource` 与 `PairRule` 继续供保护引擎使用；不要把 Room Entity 直接传给 Composable。

Repository 新增接口使用以下名称：

```kotlin
fun observeAllSources(): Flow<List<ManagedSource>>
fun observeAllRules(): Flow<List<ManagedPairRule>>
fun observeRecentEvents(limit: Int = 100): Flow<List<ActivityEvent>>
suspend fun saveManagedSource(source: ManagedSource, updatedAt: Long)
suspend fun setSourceEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Boolean
suspend fun saveManagedRule(rule: ManagedPairRule, updatedAt: Long): Long
suspend fun setRuleEnabled(id: Long, enabled: Boolean, updatedAt: Long): Boolean
suspend fun deleteRule(id: Long): Boolean
suspend fun clearAllEvents(): Int
```

`saveManagedSource` 只服务 UI，必须保留现有来源的 `createdAt`；既有 `saveSource(source: ProtectedSource, updatedAt)` 继续只服务保护领域模型和现有调用方。`setSourceEnabled` 只更改 enabled 和 updatedAt；`clearAllEvents` 只删除 `jump_events`。`saveManagedRule` 对同一 source-target-kind 进行原子 upsert；它不能删除不同 kind 的冲突规则。

## 3. Task 1：建立 UI 测试与生命周期依赖基座

**Phase:** 0

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/shakeguard/ui/UiDependencyContractTest.kt`

**Scope boundary:** 只补齐明确依赖和验证入口；不创建应用 UI，不改变 Room、服务或 Manifest 行为。

- [ ] **Step 1: 写出会失败的依赖契约测试**

创建 `UiDependencyContractTest.kt`，让它引用未来每个页面都需要的 lifecycle ViewModel 范围和 coroutine test 工具：

```kotlin
package com.shakeguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Test

class UiDependencyContractTest {
    @Test
    fun viewModelScopeAndCoroutineTestDispatcherAreAvailable() {
        val viewModel = object : ViewModel() {}

        assertNotNull(viewModel.viewModelScope)
        assertNotNull(StandardTestDispatcher())
    }
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.UiDependencyContractTest" --no-daemon --console=plain
```

预期：编译失败，提示无法解析 `viewModelScope` 或 `kotlinx.coroutines.test.StandardTestDispatcher`。

- [ ] **Step 3: 添加最小依赖集合**

在 `gradle/libs.versions.toml` 增加版本和库别名：

```toml
[libraries]
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
```

在 `app/build.gradle.kts` 增加：

```kotlin
implementation(libs.androidx.lifecycle.viewmodel.ktx)
testImplementation(libs.kotlinx.coroutines.test)
androidTestImplementation(libs.androidx.compose.ui.test.junit4)
debugImplementation(libs.androidx.compose.ui.test.manifest)
```

- [ ] **Step 4: 运行 GREEN 测试和 AndroidTest 编译**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.UiDependencyContractTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：两个命令均显示 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 验收**

满足以下全部条件：

- `viewModelScope` 和 coroutine test 在 JVM 测试中显式可用。
- Compose UI test 依赖能编译 AndroidTest。
- 不因依赖补充引入新的 runtime permission。
- 不改变任何生产行为。

- [ ] **Step 6: 提交**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/test/java/com/shakeguard/ui/UiDependencyContractTest.kt
git commit -m "build: add UI test dependencies"
```

## 4. Task 2：来源与事件的 UI 查询和清空接口

**Phase:** 0

**Files:**

- Modify: `app/src/main/java/com/shakeguard/data/Daos.kt`
- Modify: `app/src/main/java/com/shakeguard/data/Repositories.kt`
- Modify: `app/src/test/java/com/shakeguard/data/RuleRepositoryTest.kt`
- Modify: `app/src/androidTest/java/com/shakeguard/data/RoomDaoInstrumentedTest.kt`
- Modify: `app/src/test/java/com/shakeguard/ShakeGuardApplicationTest.kt`

**Scope boundary:** 只增加来源全量观察、来源暂停/恢复、最近事件映射和事件全量清空；不增加规则管理 SQL，也不创建 UI。

- [ ] **Step 1: 写出来源全量和事件清空的 RED 测试**

在 `RuleRepositoryTest.kt` 添加：

```kotlin
@Test
fun observeAllSourcesIncludesPausedSourceAndPreservesSettings() = runBlocking {
    val sourceDao = FakeProtectedSourceDao(
        listOf(
            ProtectedSourceEntity("news", true, 5_000L, true, 10L, 1L),
            ProtectedSourceEntity("video", false, 8_000L, false, 20L, 2L),
        ),
    )
    val repository = RuleRepository(sourceDao, FakePairRuleDao(), FakeJumpEventDao())

    assertEquals(
        listOf(
            ManagedSource("news", true, 5_000L, true, 1L, 10L),
            ManagedSource("video", false, 8_000L, false, 2L, 20L),
        ),
        repository.observeAllSources().first(),
    )
}

@Test
fun clearAllEventsDeletesEventsButKeepsSourceConfiguration() = runBlocking {
    val sourceDao = FakeProtectedSourceDao(
        listOf(ProtectedSourceEntity("news", false, 8_000L, false, 20L, 2L)),
    )
    val eventDao = FakeJumpEventDao().apply {
        insert(event("store", createdAt = 1L))
        insert(event("browser", createdAt = 2L))
    }
    val repository = RuleRepository(sourceDao, FakePairRuleDao(), eventDao)

    assertEquals(2, repository.clearAllEvents())
    assertTrue(repository.observeRecentEvents().first().isEmpty())
    assertEquals(ManagedSource("news", false, 8_000L, false, 2L, 20L), repository.observeAllSources().first().single())
}
```

在 `RoomDaoInstrumentedTest.kt` 添加真实 SQLite 断言：

```kotlin
@Test
fun clearAllDeletesOnlyJumpEventsAndObserveAllIncludesPausedSource() = runBlocking {
    val sources = database.protectedSourceDao()
    val events = database.jumpEventDao()
    sources.upsert(source("news", enabled = true, windowMs = 5_000L, updatedAt = 10L))
    sources.upsert(source("video", enabled = false, windowMs = 8_000L, updatedAt = 20L))
    events.insert(event("store", createdAt = 100L))
    events.insert(event("browser", createdAt = 200L))

    assertEquals(listOf("news", "video"), sources.observeAll().first().map { it.packageName }.sorted())
    assertEquals(2, events.deleteAll())
    assertTrue(events.observeRecent(100).first().isEmpty())
    assertEquals(false, sources.find("video")?.enabled)
    assertEquals(8_000L, sources.find("video")?.windowMs)
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.data.RuleRepositoryTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：编译失败，提示 `ManagedSource`、`observeAllSources`、`observeRecentEvents`、`clearAllEvents`、`observeAll` 或 `deleteAll` 尚不存在。

- [ ] **Step 3: 实现 DAO 与 Repository 的最小接口**

在 `Daos.kt` 添加：

```kotlin
@Query("SELECT * FROM protected_sources ORDER BY createdAt ASC, packageName ASC")
fun observeAll(): Flow<List<ProtectedSourceEntity>>

@Query("UPDATE protected_sources SET enabled = :enabled, updatedAt = :updatedAt WHERE packageName = :packageName")
suspend fun setEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Int

@Query("DELETE FROM jump_events")
suspend fun deleteAll(): Int
```

在 `Repositories.kt` 定义 `ManagedSource`、`ActivityEvent`，并添加：

```kotlin
fun observeAllSources(): Flow<List<ManagedSource>> =
    sourceDao.observeAll().map { entities -> entities.map { it.toManagedSource() } }

fun observeRecentEvents(limit: Int = 100): Flow<List<ActivityEvent>> =
    jumpEventDao.observeRecent(limit).map { entities -> entities.map { it.toActivityEvent() } }

suspend fun setSourceEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Boolean =
    sourceDao.setEnabled(packageName, enabled, updatedAt) == 1

suspend fun clearAllEvents(): Int = jumpEventDao.deleteAll()
```

实现 `ProtectedSourceEntity.toManagedSource()` 和 `JumpEventEntity.toActivityEvent()`；解析 `DecisionKind` 和 `ActionResult` 时用 `runCatching { valueOf(...) }`，非法持久化值不进入 UI Flow，而不是让收集 Flow 抛出异常。

同步扩展 `RuleRepositoryTest.kt` 和 `ShakeGuardApplicationTest.kt` 内的 `FakeProtectedSourceDao`、`FakeJumpEventDao`：`observeAll` 返回当前全部值，`setEnabled` 精确替换目标来源，`observeRecent(limit)` 返回按 createdAt 倒序的截断集合，`deleteAll` 清空 event 值并返回删除数。`ShakeGuardApplicationTest.kt` 中的 fake 必须同步实现 DAO 新增方法，避免 Application graph 的已有测试因接口扩展而失去编译能力。

- [ ] **Step 4: 运行 GREEN 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.data.RuleRepositoryTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：JVM 测试通过；若设备或 ADB 不可用，记录完整 ADB 错误，并改运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

此替代命令只能证明 AndroidTest 可编译，不能声称 instrumentation 已执行。

- [ ] **Step 5: 验收**

- 管理 UI 可观察启用和暂停来源；保护服务继续使用 `observeEnabled`。
- 暂停来源的时间窗、来源级阻止、createdAt 和 updatedAt 不因读取或恢复丢失。
- `observeRecentEvents()` 默认最多返回 100 条，按 createdAt 倒序。
- 清空只删除 jump_events，返回实际删除数量。
- 清空后来源仍存在，且状态和配置未变。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/shakeguard/data/Daos.kt app/src/main/java/com/shakeguard/data/Repositories.kt app/src/test/java/com/shakeguard/data/RuleRepositoryTest.kt app/src/test/java/com/shakeguard/ShakeGuardApplicationTest.kt app/src/androidTest/java/com/shakeguard/data/RoomDaoInstrumentedTest.kt
git commit -m "feat: expose sources and events for UI"
```

## 5. Task 3：组合规则管理接口与冲突保留

**Phase:** 0

**Files:**

- Modify: `app/src/main/java/com/shakeguard/data/Daos.kt`
- Modify: `app/src/main/java/com/shakeguard/data/Repositories.kt`
- Create: `app/src/main/java/com/shakeguard/data/RuleTransactionRunner.kt`
- Modify: `app/src/main/java/com/shakeguard/ShakeGuardApplication.kt`
- Modify: `app/src/test/java/com/shakeguard/data/RuleRepositoryTest.kt`
- Modify: `app/src/test/java/com/shakeguard/ShakeGuardApplicationTest.kt`
- Modify: `app/src/androidTest/java/com/shakeguard/data/RoomDaoInstrumentedTest.kt`

**Scope boundary:** 只实现规则全量读取、单条启停/删除和同 kind upsert；不创建规则界面，不改 RuleEvaluator 优先级。

- [ ] **Step 1: 写出 RED 测试，固定 ALLOW/BLOCK 冲突语义**

在 `RuleRepositoryTest.kt` 添加：

```kotlin
@Test
fun allowAndBlockForSamePairAreBothObservedAndIndependentlyMutable() = runBlocking {
    val pairDao = FakePairRuleDao(
        listOf(
            PairRuleEntity(1L, "news", "store", "ALLOW", true, "MANUAL", 10L),
            PairRuleEntity(2L, "news", "store", "BLOCK", true, "FEEDBACK", 20L),
        ),
    )
    val repository = RuleRepository(
        FakeProtectedSourceDao(emptyList()),
        pairDao,
        FakeJumpEventDao(),
        transactionRunner = RuleTransactionRunner { block -> block() },
    )

    assertEquals(setOf(RuleKind.ALLOW, RuleKind.BLOCK), repository.observeAllRules().first().map { it.kind }.toSet())
    assertTrue(repository.setRuleEnabled(1L, false, 30L))
    assertTrue(repository.deleteRule(2L))
    assertEquals(false, pairDao.all().single { it.id == 1L }.enabled)
    assertTrue(pairDao.all().none { it.id == 2L })
}

@Test
fun saveManagedRuleUpdatesSameKindWithoutDeletingOppositeKind() = runBlocking {
    val pairDao = FakePairRuleDao(
        listOf(
            PairRuleEntity(1L, "news", "store", "ALLOW", false, "MANUAL", 10L),
            PairRuleEntity(2L, "news", "store", "BLOCK", true, "FEEDBACK", 20L),
        ),
    )
    val repository = RuleRepository(
        FakeProtectedSourceDao(emptyList()),
        pairDao,
        FakeJumpEventDao(),
        transactionRunner = RuleTransactionRunner { block -> block() },
    )

    val id = repository.saveManagedRule(
        ManagedPairRule(0L, "news", "store", RuleKind.ALLOW, true, "MANUAL", 0L),
        updatedAt = 30L,
    )

    assertEquals(1L, id)
    assertEquals(2, pairDao.all().size)
    assertEquals(true, pairDao.all().single { it.id == 1L }.enabled)
    assertEquals(2L, pairDao.all().single { it.kind == "BLOCK" }.id)
}

@Test
fun concurrentSameKindUpsertsReturnOneStableIdAndKeepOppositeKind() = runTest {
    val pairDao = DelayedFakePairRuleDao(
        initial = listOf(PairRuleEntity(2L, "news", "store", "BLOCK", true, "FEEDBACK", 20L)),
    )
    val repository = RuleRepository(
        FakeProtectedSourceDao(emptyList()),
        pairDao,
        FakeJumpEventDao(),
        transactionRunner = RuleTransactionRunner { block -> block() },
    )

    val ids = coroutineScope {
        listOf(
            async(Dispatchers.Default) {
                repository.saveManagedRule(ManagedPairRule(0L, "news", "store", RuleKind.ALLOW, true, "MANUAL", 0L), 30L)
            },
            async(Dispatchers.Default) {
                repository.saveManagedRule(ManagedPairRule(0L, "news", "store", RuleKind.ALLOW, false, "MANUAL", 0L), 31L)
            },
        ).awaitAll()
    }

    assertEquals(1, ids.distinct().size)
    assertEquals(1, pairDao.all().count { it.kind == "ALLOW" })
    assertEquals(1, pairDao.all().count { it.kind == "BLOCK" })
}

private class DelayedFakePairRuleDao(
    initial: List<PairRuleEntity>,
) : PairRuleDao {
    private val values = initial.toMutableList()
    override suspend fun find(source: String, target: String): List<PairRuleEntity> =
        values.filter { it.sourcePackage == source && it.targetPackage == target && it.enabled }
    override suspend fun findByKind(source: String, target: String, kind: String): PairRuleEntity? {
        delay(50)
        return values.singleOrNull { it.sourcePackage == source && it.targetPackage == target && it.kind == kind }
    }
    override suspend fun insert(rule: PairRuleEntity): Long {
        val id = (values.maxOfOrNull { it.id } ?: 0L) + 1L
        values += rule.copy(id = id)
        return id
    }
    override suspend fun update(rule: PairRuleEntity): Int {
        val index = values.indexOfFirst { it.id == rule.id }
        if (index < 0) return 0
        values[index] = rule
        return 1
    }
    override fun observeAll(): Flow<List<PairRuleEntity>> = flowOf(values.toList())
    override suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int {
        val index = values.indexOfFirst { it.id == id }
        if (index < 0) return 0
        values[index] = values[index].copy(enabled = enabled, updatedAt = updatedAt)
        return 1
    }
    override suspend fun delete(id: Long): Int = if (values.removeAll { it.id == id }) 1 else 0
    fun all(): List<PairRuleEntity> = values.toList()
}
```

在 `RoomDaoInstrumentedTest.kt` 添加：

```kotlin
@Test
fun ruleManagementKeepsOppositeKindsAndMutatesOnlySelectedId() = runBlocking {
    val dao = database.pairRuleDao()
    val allowId = dao.insert(pair("news", "store", kind = "ALLOW", enabled = true))
    val blockId = dao.insert(pair("news", "store", kind = "BLOCK", enabled = true))

    assertEquals(setOf("ALLOW", "BLOCK"), dao.observeAll().first().map { it.kind }.toSet())
    assertEquals(1, dao.setEnabled(allowId, false, 40L))
    assertEquals(1, dao.delete(blockId))
    assertEquals(false, dao.findByKind("news", "store", "ALLOW")?.enabled)
    assertNull(dao.findByKind("news", "store", "BLOCK"))
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.data.RuleRepositoryTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：编译失败，提示 `ManagedPairRule`、`observeAllRules`、`saveManagedRule`、`setRuleEnabled`、`deleteRule`、`RuleTransactionRunner` 或对应 DAO 方法不存在。

- [ ] **Step 3: 添加精确规则 SQL 和 Repository 实现**

在 `Daos.kt` 添加：

```kotlin
@Query("SELECT * FROM pair_rules ORDER BY updatedAt DESC, id DESC")
fun observeAll(): Flow<List<PairRuleEntity>>

@Query("UPDATE pair_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int

@Query("DELETE FROM pair_rules WHERE id = :id")
suspend fun delete(id: Long): Int
```

在 `Repositories.kt` 定义 `ManagedPairRule` 并实现：

```kotlin
fun observeAllRules(): Flow<List<ManagedPairRule>> =
    pairRuleDao.observeAll().map { entities -> entities.mapNotNull { it.toManagedPairRuleOrNull() } }

suspend fun setRuleEnabled(id: Long, enabled: Boolean, updatedAt: Long): Boolean =
    pairRuleDao.setEnabled(id, enabled, updatedAt) == 1

suspend fun deleteRule(id: Long): Boolean = pairRuleDao.delete(id) == 1

suspend fun saveManagedRule(rule: ManagedPairRule, updatedAt: Long): Long =
    ruleWriteMutex.withLock {
        transactionRunner.run {
            val existing = pairRuleDao.findByKind(
                rule.sourcePackage,
                rule.targetPackage,
                rule.kind.name,
            )
            if (existing == null) {
                pairRuleDao.insert(rule.toEntity(id = 0L, updatedAt = updatedAt))
            } else {
                check(pairRuleDao.update(rule.toEntity(id = existing.id, updatedAt = updatedAt)) == 1)
                existing.id
            }
        }
    }
```

在 `RuleTransactionRunner.kt` 定义 `internal fun interface RuleTransactionRunner { suspend fun run(block: suspend () -> Long): Long }`。为保证 Task 2 的三参数 `RuleRepository` 调用仍可编译，构造函数新增 `transactionRunner: RuleTransactionRunner = RuleTransactionRunner { block -> block() }`；Task 3 同时修改 `ShakeGuardApplication.kt`，让生产 graph 显式传入调用 `AppDatabase.withTransaction { block() }` 的 runner。`ruleWriteMutex` 是 Repository 内的 `Mutex`，包住查询和 insert/update；它提供单进程内并发的原子保证。当前 Android 应用只支持单进程，不承诺跨进程唯一性，也不引入多进程数据库写入语义。`toManagedPairRuleOrNull()` 必须解析 `RuleKind`；无法解析的旧数据被过滤并由日志或测试可见，不能让整条 Flow 失败。查询只匹配 source、target、kind，因此 ALLOW 保存不能删除 BLOCK，BLOCK 保存不能删除 ALLOW。

扩展 `FakePairRuleDao` 的 `observeAll`、`setEnabled`、`delete`，并让 `all()` 保持测试辅助方法；`ShakeGuardApplicationTest.kt` 的 `EmptyPairRuleDao` 也必须实现这些新增 DAO 方法。并发 RED 测试使用 `coroutineScope`、`async(Dispatchers.Default)`，让 fake DAO 在 `findByKind` 设置延迟或屏障，证明没有重复 ALLOW 行且预存 BLOCK 仍在。

- [ ] **Step 4: 运行 GREEN 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.data.RuleRepositoryTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：指定 JVM 测试、并发测试和完整 JVM suite 通过；设备可用时 instrumentation 通过。设备不可用时，使用 `:app:compileDebugAndroidTestKotlin` 记录编译结果和未执行原因。

- [ ] **Step 5: 验收**

- UI 能观察所有规则，包括停用规则。
- 同一 source-target 的 ALLOW 与 BLOCK 同时保留并可分别操作。
- 启停和删除按规则 ID 精确生效。
- 保存同 kind 规则保持稳定 ID，不增加重复行。
- 并发保存同 kind 规则只产生一行并返回同一 ID；Mutex 的保证边界明确为单进程，另一 kind 的冲突规则不被删除。
- 现有 `RuleEvaluator` 优先级没有改动。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/shakeguard/data/Daos.kt app/src/main/java/com/shakeguard/data/Repositories.kt app/src/main/java/com/shakeguard/data/RuleTransactionRunner.kt app/src/test/java/com/shakeguard/data/RuleRepositoryTest.kt app/src/test/java/com/shakeguard/ShakeGuardApplicationTest.kt app/src/androidTest/java/com/shakeguard/data/RoomDaoInstrumentedTest.kt
git commit -m "feat: add rule management repository"
```

## 6. Task 4：SettingsStore、ProtectionGate 与 Application graph

**Phase:** 0

**Files:**

- Create: `app/src/main/java/com/shakeguard/protection/ProtectionGate.kt`
- Modify: `app/src/main/java/com/shakeguard/data/SettingsStore.kt`
- Modify: `app/src/main/java/com/shakeguard/ShakeGuardApplication.kt`
- Modify: `app/src/test/java/com/shakeguard/ShakeGuardApplicationTest.kt`
- Create: `app/src/test/java/com/shakeguard/protection/ProtectionGateTest.kt`

**依赖与范围边界:** Task 3 已在 Application graph 接入规则事务 runner。本任务在该 graph 上增加进程级 SettingsStore、fail-closed ProtectionGate 和共享实例；不修改 `ShakeGuardAccessibilityService.kt`、`ProtectionCoordinator.kt`、通知流程、RuleEvaluator 或 Room schema。Task 5 才把已经存在于 graph 的 Gate 接入服务。

- [ ] **Step 1: 写出 Gate RED 测试**

创建 `ProtectionGateTest.kt`：

```kotlin
package com.shakeguard.protection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionGateTest {
    @Test
    fun gateIsNotReadyBeforeFirstDataStoreValueAndBecomesDisabledAfterFalse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = FakeProtectionEnabledStore()
        val gate = SettingsProtectionGate(store, scope)

        assertEquals(ProtectionGateState.NotReady, gate.state.value)
        advanceUntilIdle()
        store.emit(false)
        advanceUntilIdle()
        assertEquals(ProtectionGateState.Disabled, gate.state.value)

        scope.cancel()
    }

    @Test
    fun gateBecomesEnabledOnlyAfterFirstPersistedTrue() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = FakeProtectionEnabledStore()
        val gate = SettingsProtectionGate(store, scope)

        assertEquals(ProtectionGateState.NotReady, gate.state.value)
        advanceUntilIdle()
        store.emit(true)
        advanceUntilIdle()
        assertEquals(ProtectionGateState.Enabled, gate.state.value)

        scope.cancel()
    }
}

private class FakeProtectionEnabledStore : ProtectionEnabledStore {
    private val state = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 1)
    override val enabled = state
    fun emit(value: Boolean) { state.tryEmit(value) }
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.protection.ProtectionGateTest" --tests "com.shakeguard.ShakeGuardApplicationTest" --no-daemon --console=plain
```

预期：编译失败，提示 `ProtectionEnabledStore`、`SettingsProtectionGate`、`ProtectionGateState`、`settingsStoreFactory` 或 `protectionGate` 不存在。

- [ ] **Step 3: 实现 Gate 接口与 Application graph**

在 `ProtectionGate.kt` 定义：

```kotlin
package com.shakeguard.protection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

interface ProtectionEnabledStore {
    val enabled: Flow<Boolean>
}

interface ProtectionSettings : ProtectionEnabledStore {
    val windowMs: Flow<Long>
    val notifications: Flow<Boolean>
    suspend fun setEnabled(value: Boolean)
    suspend fun setWindowMs(value: Long)
    suspend fun setNotifications(value: Boolean)
}

interface ProtectionGate {
    val state: StateFlow<ProtectionGateState>
}

enum class ProtectionGateState { NotReady, Enabled, Disabled }

class SettingsProtectionGate(
    store: ProtectionEnabledStore,
    scope: CoroutineScope,
) : ProtectionGate {
    override val state: StateFlow<ProtectionGateState> = store.enabled
        .map { enabled -> if (enabled) ProtectionGateState.Enabled else ProtectionGateState.Disabled }
        .stateIn(scope, SharingStarted.Eagerly, ProtectionGateState.NotReady)
}
```

让 `SettingsStore` 实现 `ProtectionSettings`，其现有 `enabled`、`windowMs`、`notifications` 和三个 setter 保持现有默认值与约束。

在 `ShakeGuardApplicationTest.kt` 同一 RED 步骤加入以下 graph 断言，并让 `FakeSettingsStore` 以 `MutableStateFlow` 提供三项设置值：

```kotlin
@Test
fun dependencyGraphSharesSettingsStoreAndFailClosedProtectionGate() {
    val settings = FakeSettingsStore()
    val graph = ShakeGuardDependencyGraph(
        databaseFactory = { FakeAppDatabase() },
        settingsStoreFactory = { settings },
        applicationScope = CoroutineScope(SupervisorJob()),
    )

    assertSame(settings, graph.settingsStore)
    assertSame(graph.protectionGate, graph.protectionGate)
    assertEquals(ProtectionGateState.NotReady, graph.protectionGate.state.value)
}
```

扩展 `ShakeGuardDependencyGraph` 的构造函数以接收 `settingsStoreFactory`、`applicationScope` 与 Task 3 的 `RuleTransactionRunner` factory；生产 graph 创建 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`，用 `SettingsStore(this)` 和 `AppDatabase.withTransaction { block() }` 构造这些依赖，并暴露只读 `settingsStore` 与 `protectionGate`。Activity、ViewModel 和服务从同一 Application 获取同一实例，Application 进程终止时由系统回收 scope。

- [ ] **Step 4: 运行 GREEN 测试并验证 graph**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.protection.ProtectionGateTest" --tests "com.shakeguard.ShakeGuardApplicationTest" --no-daemon --console=plain
```

预期：指定测试通过；Gate 测试证明首个 DataStore 值到达前是 `NotReady`，不把未就绪当作 Enabled，并在每个测试末尾取消测试 scope。

- [ ] **Step 5: 验收**

- Application graph 在服务接入前提供同一进程级 `SettingsStore` 与 `ProtectionGate`；Task 3 的规则事务 runner 继续保留。
- 第一个 DataStore 值到达前 Gate 为 `NotReady`，不得执行 Back、写 BLOCK event 或发送通知。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/shakeguard/protection/ProtectionGate.kt app/src/main/java/com/shakeguard/data/SettingsStore.kt app/src/main/java/com/shakeguard/ShakeGuardApplication.kt app/src/test/java/com/shakeguard/ShakeGuardApplicationTest.kt app/src/test/java/com/shakeguard/protection/ProtectionGateTest.kt
git commit -m "feat: add fail-closed protection gate graph"
```

## 7. Task 5：将 ProtectionGate 接入无障碍服务

**Phase:** 0

**Files:**

- Modify: `app/src/main/java/com/shakeguard/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/shakeguard/protection/SessionTracker.kt`
- Modify: `app/src/main/java/com/shakeguard/accessibility/ShakeGuardAccessibilityService.kt`
- Modify: `app/src/test/java/com/shakeguard/protection/ProtectionCoordinatorTest.kt`
- Modify: `app/src/test/java/com/shakeguard/protection/SessionTrackerTest.kt`
- Modify: `app/src/test/java/com/shakeguard/accessibility/FirstBlockNotificationTest.kt`

**依赖与范围边界:** 依赖 Task 4 已接线的 `applicationGraph.protectionGate`；只修改服务事件入口和保护会话失效语义，不改 Application graph、RuleEvaluator、FeedbackHandler、通知 PendingIntent 或 Room schema。

- [ ] **Step 1: 写出服务 Gate RED 测试**

在 `FirstBlockNotificationTest.kt` 添加服务 helper RED 测试：

```kotlin
@Test
fun disabledOrNotReadyClearsSessionAndSkipsProtectionSideEffects() = runTest {
    var evaluateCalls = 0
    var notificationCalls = 0
    var clearCalls = 0

    handleForegroundForProtection(
        packageName = "store",
        gateState = ProtectionGateState.Disabled,
        clearSession = { clearCalls += 1 },
        evaluate = { evaluateCalls += 1; blockedOutcome(42L) },
        publishFirstBlock = { notificationCalls += 1 },
    )
    handleForegroundForProtection(
        packageName = "store",
        gateState = ProtectionGateState.NotReady,
        clearSession = { clearCalls += 1 },
        evaluate = { evaluateCalls += 1; blockedOutcome(43L) },
        publishFirstBlock = { notificationCalls += 1 },
    )

    assertEquals(2, clearCalls)
    assertEquals(0, evaluateCalls)
    assertEquals(0, notificationCalls)
}
```

该测试使用 `runTest`、`StandardTestDispatcher(testScheduler)` 和 `advanceUntilIdle()`；测试文件补齐 `runTest`、`StandardTestDispatcher`、`advanceUntilIdle` 与 `ProtectionGateState` 的 imports。Task 4 的 Gate 测试负责验证并取消它创建的 scope。

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.accessibility.FirstBlockNotificationTest" --tests "com.shakeguard.protection.ProtectionCoordinatorTest" --tests "com.shakeguard.protection.SessionTrackerTest" --no-daemon --console=plain
```

预期：编译失败，提示 `ProtectionGateState`、`gateState`、`clearSession`、`clearProtectionSession`、`reset` 或 `handleForegroundForProtection` 不存在。

- [ ] **Step 3: 实现会话清理和服务入口**

在 `SessionTracker.kt` 增加：

```kotlin
fun reset() {
    state = SessionState.Idle
    lastTransition = null
    lastTransitionAt = null
}
```

在 `ProtectionCoordinator.kt` 增加 `fun clearProtectionSession() = sessionTracker.reset()`。在 `ShakeGuardAccessibilityService.kt` 提取并使用以下入口；服务传入 `applicationGraph.protectionGate.state.value`、`coordinator::clearProtectionSession`、`coordinator::onForeground` 与现有首次阻断通知发布 lambda：

```kotlin
internal suspend fun handleForegroundForProtection(
    packageName: String,
    gateState: ProtectionGateState,
    clearSession: () -> Unit,
    evaluate: suspend (String) -> ProtectionOutcome?,
    publishFirstBlock: suspend (ProtectionOutcome) -> Unit,
) {
    if (gateState != ProtectionGateState.Enabled) {
        clearSession()
        return
    }
    val outcome = evaluate(packageName) ?: return
    publishFirstBlock(outcome)
}
```

`NotReady` 和 `Disabled` 均直接放行，并清除来源保护会话。重新变为 Enabled 后，只有新的来源前台事件才能建立会话；单独目标事件不能复用关闭前的来源会话。这定义了立即恢复的边界：下一次新的来源到目标跳转恢复正常保护。

- [ ] **Step 4: 写出会话生命周期 RED 测试**

在 `ProtectionCoordinatorTest.kt` 添加：

```kotlin
@Test
fun disabledSessionIsClearedAndReenableRequiresFreshSourceEvent() = runTest {
    val clock = FakeClock(1_000L)
    val back = FakeBackAction(succeeds = true)
    val recorder = FakeRecorder()
    val coordinator = coordinator(clock, back, recorder)

    coordinator.onForeground("news")
    coordinator.clearProtectionSession()
    assertEquals(null, coordinator.onForeground("store"))
    assertEquals(0, back.callCount)
    assertEquals(0, recorder.records.size)

    coordinator.onForeground("news")
    clock.advanceBy(100L)
    val outcome = coordinator.onForeground("store")

    assertEquals(DecisionKind.BLOCK, outcome?.decision?.kind)
    assertEquals(1, back.callCount)
    assertEquals(1, recorder.records.size)
}
```

该测试使用 `runTest`、`StandardTestDispatcher(testScheduler)` 和 `advanceUntilIdle()`；`FirstBlockNotificationTest.kt` 必须分别断言 `NotReady` 与 `Disabled` 都不调用评估或通知 lambda，且各调用一次清会话 lambda。

- [ ] **Step 5: 运行 GREEN 测试和完整 JVM 回归**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.accessibility.FirstBlockNotificationTest" --tests "com.shakeguard.protection.ProtectionCoordinatorTest" --tests "com.shakeguard.protection.SessionTrackerTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --no-daemon --console=plain
```

预期：指定服务、会话和通知测试以及完整 JVM suite 均成功。

- [ ] **Step 6: 验收**

- Gate 未就绪或关闭时服务保持绑定、直接放行，不执行 Back、不写 BLOCK event、不发送首次阻断通知。
- Gate 关闭事件会清除旧保护会话；重新开启后，目标事件不能复用旧来源，必须先出现新的来源事件。
- Gate 开启且经过新来源事件后，既有保护评估、Back、BLOCK event 和首次通知流程保持不变。

- [ ] **Step 7: 提交**

```powershell
git add app/src/main/java/com/shakeguard/protection/ProtectionCoordinator.kt app/src/main/java/com/shakeguard/protection/SessionTracker.kt app/src/main/java/com/shakeguard/accessibility/ShakeGuardAccessibilityService.kt app/src/test/java/com/shakeguard/protection/ProtectionCoordinatorTest.kt app/src/test/java/com/shakeguard/protection/SessionTrackerTest.kt app/src/test/java/com/shakeguard/accessibility/FirstBlockNotificationTest.kt
git commit -m "feat: enforce gate session lifecycle in service"
```

## 8. Task 6：Compose 根壳、路由和三项底部导航

**Phase:** 1

**Files:**

- Modify: `app/src/main/java/com/shakeguard/MainActivity.kt`
- Create: `app/src/main/java/com/shakeguard/ui/ShakeGuardApp.kt`
- Create: `app/src/main/java/com/shakeguard/ui/navigation/AppRoutes.kt`
- Create: `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- Create: `app/src/androidTest/java/com/shakeguard/ui/AppNavigationInstrumentedTest.kt`

**Scope boundary:** 只建立根主题、路由和三项主导航；页面内容使用稳定的最小 Composable，但不实现首页业务状态、来源、规则或日志数据。

- [ ] **Step 1: 写出导航 RED UI 测试**

创建 `AppNavigationInstrumentedTest.kt`：

```kotlin
package com.shakeguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppNavigationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun bottomNavigationHasExactlyHomeRulesAndActivity() {
        composeRule.setContent { ShakeGuardApp() }

        composeRule.onNodeWithText("首页").assertIsDisplayed()
        composeRule.onNodeWithText("规则").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("规则页面").assertIsDisplayed()
        composeRule.onNodeWithText("活动").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("活动页面").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: 运行 RED UI 测试编译**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：编译失败，提示 `ShakeGuardApp` 不存在。

- [ ] **Step 3: 实现路由和根壳**

在 `AppRoutes.kt` 定义：

```kotlin
object AppRoutes {
    const val HOME = "home"
    const val RULES = "rules"
    const val ACTIVITY = "activity"
    const val APP_PICKER = "app-picker"
    const val SOURCE = "source/{packageName}"
    const val RULE_EDITOR = "rule-editor?ruleId={ruleId}"

    fun source(packageName: String) = "source/$packageName"
    fun ruleEditor(ruleId: Long?) = ruleId?.let { "rule-editor?ruleId=$it" } ?: "rule-editor"
}
```

`ShakeGuardApp` 用 MaterialTheme、Scaffold、NavigationBar 和 AppNavHost 构成根。NavigationBar 只创建三项：`首页`、`规则`、`活动`。`AppNavHost` 注册三项主路由和 app-picker/source/rule-editor 子路由；每个未实现业务的页面显示稳定的标题，例如“首页页面”“规则页面”“活动页面”，以便 UI 测试定位。

修改 `MainActivity.kt`：

```kotlin
setContent { ShakeGuardApp() }
```

不得在 MainActivity 中创建 NavController、ViewModel、Room 或 PackageManager 查询。

- [ ] **Step 4: 运行 GREEN 编译、设备测试和构建**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDebug --no-daemon --console=plain
```

预期：编译和 assemble 成功；设备可用时 UI 测试通过。设备不可用时记录原因，不能把编译成功写成设备 UI 成功。

- [ ] **Step 5: 验收**

- 应用启动后不再显示旧的静态 UI。
- 底部导航仅有首页、规则、活动三项。
- 子路由存在但不增加底部导航项。
- MainActivity 只承担 Compose 入口职责。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/java/com/shakeguard/MainActivity.kt app/src/main/java/com/shakeguard/ui/ShakeGuardApp.kt app/src/main/java/com/shakeguard/ui/navigation/AppRoutes.kt app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt app/src/androidTest/java/com/shakeguard/ui/AppNavigationInstrumentedTest.kt
git commit -m "feat: add Compose navigation shell"
```

## 9. Task 7：首页状态、无障碍授权和通知可选引导

**Phase:** 1

**Files:**

- Create: `app/src/main/java/com/shakeguard/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/shakeguard/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/com/shakeguard/ui/system/AccessibilityStatusReader.kt`
- Create: `app/src/main/java/com/shakeguard/ui/system/NotificationCapabilityReader.kt`
- Modify: `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- Create: `app/src/test/java/com/shakeguard/ui/home/HomeViewModelTest.kt`
- Create: `app/src/androidTest/java/com/shakeguard/ui/home/HomeScreenInstrumentedTest.kt`

**Scope boundary:** 实现首页及授权状态；不实现应用选择、规则编辑或 ActivityLog 的实际列表。

- [ ] **Step 1: 写出 HomeViewModel RED 测试**

创建 `HomeViewModelTest.kt`，使用 fake 接口：

```kotlin
@Test
fun accessibilityDisabledTakesPrecedenceOverDisabledProtection() = runTest {
    val viewModel = HomeViewModel(
        protectionGate = FakeGate(ProtectionGateState.Disabled),
        sourceRepository = FakeHomeSourceRepository(configured = 3, paused = 1),
        accessibilityStatusReader = FakeAccessibilityStatusReader(false),
        notificationCapabilityReader = FakeNotificationCapabilityReader(NotificationCapability.Unavailable),
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    advanceUntilIdle()

    assertEquals(HomeStatus.AccessibilityDisabled, viewModel.uiState.value.status)
}

@Test
fun disabledGateShowsServiceStillConnectedAndTogglesDataStore() = runTest {
    val settings = FakeHomeSettingsStore(enabled = false)
    val viewModel = HomeViewModel(
        protectionGate = FakeGate(ProtectionGateState.Disabled),
        sourceRepository = FakeHomeSourceRepository(configured = 2, paused = 1),
        accessibilityStatusReader = FakeAccessibilityStatusReader(true),
        notificationCapabilityReader = FakeNotificationCapabilityReader(NotificationCapability.Available),
        settingsStore = settings,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    advanceUntilIdle()
    assertEquals(HomeStatus.ProtectionDisabled, viewModel.uiState.value.status)
    assertTrue(viewModel.uiState.value.serviceConnected)
    viewModel.setProtectionEnabled(true)
    assertEquals(true, settings.lastEnabled)
}
```

定义最小状态：

```kotlin
enum class HomeStatus { Loading, AccessibilityDisabled, ProtectionDisabled, NoSources, Active, Error }
enum class NotificationCapability { Available, PermissionDenied, SystemDisabled, Unavailable }

private class FakeGate(initial: ProtectionGateState) : ProtectionGate {
    override val state = MutableStateFlow(initial)
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.home.HomeViewModelTest" --no-daemon --console=plain
```

预期：编译失败，提示 HomeViewModel、状态类型和系统 reader 接口不存在。

- [ ] **Step 3: 实现系统适配器和 ViewModel**

`AccessibilityStatusReader.kt` 定义：

```kotlin
fun interface AccessibilityStatusReader {
    fun isEnabled(): Boolean
}
```

Android adapter 使用 `AccessibilityManager.getEnabledAccessibilityServiceList` 检查当前包的 `ShakeGuardAccessibilityService`，只返回 Boolean。

`NotificationCapabilityReader.kt` 定义：

```kotlin
fun interface NotificationCapabilityReader {
    fun read(): NotificationCapability
}
```

Android adapter 检查 Android 13+ `POST_NOTIFICATIONS`、`NotificationManagerCompat.areNotificationsEnabled()` 和当前 channel；任何 RuntimeException 返回 `Unavailable`，不抛给 Composable。

HomeViewModel 合并 `ProtectionGate.state`、`observeAllSources()`、两个 system reader 和 SettingsStore。`NotReady` 映射为 `Loading`，`Disabled` 映射为保护关闭，`Enabled` 才参与来源状态判断。状态优先级固定：无障碍未授权高于保护关闭，保护关闭高于没有来源，没有来源高于 Active。`setProtectionEnabled` 在 viewModelScope 中调用 `settingsStore.setEnabled`。`refreshSystemStatus` 在页面恢复后重新读取 reader。

- [ ] **Step 4: 写出 HomeScreen RED UI 测试**

在 `HomeScreenInstrumentedTest.kt` 添加：

```kotlin
@Test
fun disabledProtectionExplainsThatServiceRemainsConnected() {
    composeRule.setContent {
        HomeScreen(
            state = HomeUiState(
                status = HomeStatus.ProtectionDisabled,
                serviceConnected = true,
                configuredSourceCount = 2,
                pausedSourceCount = 1,
                notificationCapability = NotificationCapability.Available,
            ),
            onSetProtectionEnabled = {},
            onOpenAccessibilitySettings = {},
            onOpenNotificationSettings = {},
            onAddSource = {},
            onOpenActivity = {},
        )
    }

    composeRule.onNodeWithText("保护已关闭，服务仍保持连接").assertIsDisplayed()
}
```

- [ ] **Step 5: 实现 HomeScreen 并接入导航**

HomeScreen 对每个 HomeStatus 提供独立、稳定的可测试文案：

- `AccessibilityDisabled`：显示“需要系统授权”和“打开无障碍设置”。
- `ProtectionDisabled`：显示“保护已关闭，服务仍保持连接”。
- `NoSources`：显示“尚未添加来源应用”和“添加来源应用”。
- `Active`：显示“保护中”、已保护来源数、已暂停来源数和活动入口。
- 通知不可用：显示可选提示“通知提醒不可用”，不遮挡或替换保护状态。

在 AppNavHost 将 HomeScreen 作为 `home` 路由内容。Activity 通过 `onResume` 或 `LifecycleEventObserver` 调用 `refreshSystemStatus`；打开无障碍设置使用 `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`，通知设置使用 `Settings.ACTION_APP_NOTIFICATION_SETTINGS`。Intent 无法解析时发出错误 effect。

- [ ] **Step 6: 运行 GREEN 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.home.HomeViewModelTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：JVM 测试通过；设备可用时首页 UI 测试通过。无设备时只报告 AndroidTest 编译成功。

- [ ] **Step 7: 验收**

- 首页可区分未授权、服务连接但保护关闭、无来源、保护中和读取错误。
- 通知拒绝或系统通知关闭只显示可选降级状态，不改变核心保护状态。
- 用户可从首页打开系统无障碍设置，返回后状态刷新。
- 全局开关写入 SettingsStore，服务仍保持绑定。
- 首页不直接使用 DAO、Room 或 PackageManager。

- [ ] **Step 8: 提交**

```powershell
git add app/src/main/java/com/shakeguard/ui/home/HomeViewModel.kt app/src/main/java/com/shakeguard/ui/home/HomeScreen.kt app/src/main/java/com/shakeguard/ui/system/AccessibilityStatusReader.kt app/src/main/java/com/shakeguard/ui/system/NotificationCapabilityReader.kt app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt app/src/test/java/com/shakeguard/ui/home/HomeViewModelTest.kt app/src/androidTest/java/com/shakeguard/ui/home/HomeScreenInstrumentedTest.kt
git commit -m "feat: add protection status home"
```

## 10. Task 8：应用目录与来源选择

**Phase:** 2

**Files:**

- Create: `app/src/main/java/com/shakeguard/ui/system/AppCatalog.kt`
- Create: `app/src/main/java/com/shakeguard/ui/sources/SourcesViewModel.kt`
- Create: `app/src/main/java/com/shakeguard/ui/sources/SourcePickerScreen.kt`
- Modify: `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- Create: `app/src/test/java/com/shakeguard/ui/sources/SourcesViewModelTest.kt`
- Create: `app/src/androidTest/java/com/shakeguard/ui/sources/SourcePickerScreenInstrumentedTest.kt`

**Scope boundary:** 实现可启动应用目录、搜索和进入来源详情；不在本任务实现时间窗编辑表单。

- [ ] **Step 1: 写出 AppCatalog 和选择器 RED 测试**

在 `SourcesViewModelTest.kt` 添加：

```kotlin
@Test
fun pickerSearchesLabelsAndPackagesAndExcludesShakeGuardItself() = runTest {
    val catalog = FakeAppCatalog(
        listOf(
            InstalledApp("com.shakeguard", "ShakeGuard", null),
            InstalledApp("news.app", "每日新闻", null),
            InstalledApp("video.app", "Video", null),
        ),
    )
    val viewModel = SourcesViewModel(catalog, FakeSourceRepository(), StandardTestDispatcher(testScheduler))

    advanceUntilIdle()
    viewModel.setQuery("news")

    assertEquals(listOf("news.app"), viewModel.pickerState.value.visibleApps.map { it.packageName })
    assertTrue(viewModel.pickerState.value.visibleApps.none { it.packageName == "com.shakeguard" })
}

@Test
fun pickerMarksConfiguredAndPausedSourcesWithoutPersistingLabels() = runTest {
    val repository = FakeSourceRepository(
        sources = listOf(ManagedSource("news.app", false, 5_000L, true, 1L, 2L)),
    )
    val viewModel = SourcesViewModel(
        FakeAppCatalog(listOf(InstalledApp("news.app", "每日新闻", null))),
        repository,
        StandardTestDispatcher(testScheduler),
    )

    advanceUntilIdle()

    assertEquals(SourcePickerStatus.Paused, viewModel.pickerState.value.visibleApps.single().status)
    assertEquals("news.app", repository.sources.single().packageName)
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.sources.SourcesViewModelTest" --no-daemon --console=plain
```

预期：编译失败，提示 AppCatalog、InstalledApp、SourcesViewModel 或 SourcePickerStatus 不存在。

- [ ] **Step 3: 实现 AppCatalog 和 SourcesViewModel**

在 `AppCatalog.kt` 定义：

```kotlin
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

fun interface AppCatalog {
    suspend fun loadLaunchableApps(): List<InstalledApp>
}
```

Android adapter 在 `Dispatchers.IO` 查询 `ACTION_MAIN`/`CATEGORY_LAUNCHER` 的 ResolveInfo，去重 packageName，排除自身包名，按 label 再 packageName 排序。label 为空回退 packageName；图标解析失败返回 null。此模块不向 Room 写入 label 或 icon。

SourcesViewModel 组合 AppCatalog 与 `observeAllSources()`。定义 `SourcePickerStatus` 为 `Unconfigured`、`Protected`、`Paused`；搜索以 label 和 packageName 的不区分大小写包含匹配。ViewModel 用稳定 packageName 将来源配置合并到 AppCatalog 条目。

- [ ] **Step 4: 写出 SourcePickerScreen RED UI 测试**

在 `SourcePickerScreenInstrumentedTest.kt` 添加：

```kotlin
@Test
fun pausedSourceIsVisibleAndSelectingItOpensItsDetail() {
    var selected: String? = null
    composeRule.setContent {
        SourcePickerScreen(
            state = SourcePickerUiState(
                loading = false,
                visibleApps = listOf(SourcePickerItem("news.app", "每日新闻", null, SourcePickerStatus.Paused)),
            ),
            onQueryChanged = {},
            onSelectPackage = { selected = it },
        )
    }

    composeRule.onNodeWithText("每日新闻").assertIsDisplayed().performClick()
    composeRule.onNodeWithText("已暂停").assertIsDisplayed()
    assertEquals("news.app", selected)
}
```

- [ ] **Step 5: 实现选择器并接入子路由**

SourcePickerScreen 使用 LazyColumn、稳定 packageName key、搜索 TextField 和状态标签。点击任意条目调用 `onSelectPackage(packageName)`；AppNavHost 导航到 `AppRoutes.source(packageName)`。新来源的创建延后到来源详情首次保存，避免点击选择器立即写入默认配置。

- [ ] **Step 6: 运行 GREEN 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.sources.SourcesViewModelTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：JVM 测试和 AndroidTest 编译成功。

- [ ] **Step 7: 验收**

- 用户能按应用名或包名搜索可启动应用。
- ShakeGuard 自身不会出现在来源选择列表。
- 应用 label/icon 只用于运行时展示，Repository 保存的来源只有 packageName 和配置。
- 已暂停来源显示“已暂停”，点击后进入详情，不会删除或重建来源。
- PackageManager 工作在后台线程，列表使用 LazyColumn。

- [ ] **Step 8: 提交**

```powershell
git add app/src/main/java/com/shakeguard/ui/system/AppCatalog.kt app/src/main/java/com/shakeguard/ui/sources/SourcesViewModel.kt app/src/main/java/com/shakeguard/ui/sources/SourcePickerScreen.kt app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt app/src/test/java/com/shakeguard/ui/sources/SourcesViewModelTest.kt app/src/androidTest/java/com/shakeguard/ui/sources/SourcePickerScreenInstrumentedTest.kt
git commit -m "feat: add source app picker"
```

## 11. Task 9：来源详情、暂停/恢复与每来源时间窗

**Phase:** 2

**Files:**

- Modify: `app/src/main/java/com/shakeguard/ui/sources/SourcesViewModel.kt`
- Create: `app/src/main/java/com/shakeguard/ui/sources/SourceDetailScreen.kt`
- Modify: `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- Modify: `app/src/test/java/com/shakeguard/ui/sources/SourcesViewModelTest.kt`
- Create: `app/src/androidTest/java/com/shakeguard/ui/sources/SourceDetailScreenInstrumentedTest.kt`

**Scope boundary:** 完成来源配置的保存、暂停和恢复；不提供来源删除，规则数量只在此处显示或链接，不编辑规则。

- [ ] **Step 1: 写出来源保存 RED 测试**

在 `SourcesViewModelTest.kt` 添加：

```kotlin
@Test
fun resumingPausedSourceChangesOnlyEnabledAndKeepsWindowAndSourceBlock() = runTest {
    val source = ManagedSource("news.app", false, 8_000L, false, 1L, 20L)
    val repository = FakeSourceRepository(sources = listOf(source))
    val viewModel = SourcesViewModel(FakeAppCatalog(emptyList()), repository, StandardTestDispatcher(testScheduler))

    viewModel.setSourceEnabled("news.app", true)
    advanceUntilIdle()

    assertEquals(ManagedSource("news.app", true, 8_000L, false, 1L, repository.lastUpdatedAt), repository.sources.single())
}

@Test
fun firstSaveUsesDefaultWindowAndPreservesSelectedPackage() = runTest {
    val repository = FakeSourceRepository()
    val viewModel = SourcesViewModel(
        FakeAppCatalog(listOf(InstalledApp("news.app", "每日新闻", null))),
        repository,
        StandardTestDispatcher(testScheduler),
        defaultWindowMs = 5_000L,
    )

    viewModel.saveManagedSource("news.app", enabled = true, windowMs = 5_000L, sourceLevelBlock = true)
    advanceUntilIdle()

    assertEquals("news.app", repository.sources.single().packageName)
    assertEquals(5_000L, repository.sources.single().windowMs)
    assertTrue(repository.sources.single().sourceLevelBlock)
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.sources.SourcesViewModelTest" --no-daemon --console=plain
```

预期：测试失败，因为 SourcesViewModel 尚未提供来源详情保存和启停意图。

- [ ] **Step 3: 实现详情状态与保存逻辑**

定义：

```kotlin
data class SourceDetailUiState(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val enabled: Boolean,
    val windowMs: Long,
    val sourceLevelBlock: Boolean,
    val relatedRuleCount: Int,
    val saving: Boolean,
    val errorMessage: String?,
)
```

SourcesViewModel 从 `observeAllSources()` 读取具体来源。不存在来源时以选定 packageName、DataStore `windowMs.first()` 和 `sourceLevelBlock = true` 创建编辑初始值；不会在打开详情时持久化。`saveManagedSource` 调用 Task 2 的 `RuleRepository.saveManagedSource(ManagedSource, updatedAt)`，其中已有来源保留 createdAt。该 UI 方法与保护领域的 `saveSource(ProtectedSource, updatedAt)` 名称和职责分离。`setSourceEnabled` 调用 `setSourceEnabled(packageName, enabled, updatedAt)`，失败时产生错误 effect。

在 Repository 增加 `countRulesForSource(packageName: String): Flow<Int>`，实现为 `observeAllRules().map { rules -> rules.count { it.sourcePackage == packageName } }`，不追加专用 SQL。

- [ ] **Step 4: 写出来源详情 GREEN 前的 UI RED 测试**

在 `SourceDetailScreenInstrumentedTest.kt` 添加：

```kotlin
@Test
fun pausedSourceShowsResumeAndKeepsItsConfiguredWindow() {
    composeRule.setContent {
        SourceDetailScreen(
            state = SourceDetailUiState("news.app", "每日新闻", null, false, 8_000L, false, 2, false, null),
            onEnabledChanged = {},
            onWindowChanged = {},
            onSourceLevelBlockChanged = {},
            onSave = {},
            onOpenRules = {},
            onBack = {},
        )
    }

    composeRule.onNodeWithText("已暂停").assertIsDisplayed()
    composeRule.onNodeWithText("恢复保护").assertIsDisplayed()
    composeRule.onNodeWithText("8 秒").assertIsDisplayed()
}
```

- [ ] **Step 5: 实现 SourceDetailScreen 并接入保存**

页面显示图标、应用名、包名、保护状态、开关、时间窗、来源级阻止开关、相关规则数量和“查看相关规则”。时间窗使用固定 1-30 秒的 Slider 与数值输入；输入值在 UI 中转换为毫秒，Repository 仍负责最终约束。暂停来源显示“已暂停”和“恢复保护”。保存失败时保留编辑字段并显示错误，不自动返回。

- [ ] **Step 6: 运行 GREEN 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.sources.SourcesViewModelTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：JVM 测试和 AndroidTest 编译成功。

- [ ] **Step 7: 验收**

- 来源详情能创建新来源并保存 packageName、时间窗、来源级阻止状态。
- 暂停来源仍可编辑所有来源配置。
- 恢复只更新 enabled，保留 createdAt、windowMs、sourceLevelBlock 和规则。
- 时间窗展示秒数，持久化为毫秒。
- 保存失败不会丢失用户已输入的编辑值。

- [ ] **Step 8: 提交**

```powershell
git add app/src/main/java/com/shakeguard/data/Repositories.kt app/src/main/java/com/shakeguard/ui/sources/SourcesViewModel.kt app/src/main/java/com/shakeguard/ui/sources/SourceDetailScreen.kt app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt app/src/test/java/com/shakeguard/ui/sources/SourcesViewModelTest.kt app/src/androidTest/java/com/shakeguard/ui/sources/SourceDetailScreenInstrumentedTest.kt
git commit -m "feat: configure and resume protected sources"
```

## 12. Task 10：规则列表、冲突标记和编辑器

**Phase:** 3

**Files:**

- Create: `app/src/main/java/com/shakeguard/ui/rules/RulesViewModel.kt`
- Create: `app/src/main/java/com/shakeguard/ui/rules/RulesScreen.kt`
- Create: `app/src/main/java/com/shakeguard/ui/rules/RuleEditorScreen.kt`
- Modify: `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- Create: `app/src/test/java/com/shakeguard/ui/rules/RulesViewModelTest.kt`
- Create: `app/src/androidTest/java/com/shakeguard/ui/rules/RulesScreenInstrumentedTest.kt`

**Scope boundary:** 用 Task 3 的 Repository 接口完成规则 UI；不改 RuleEvaluator 的 ALLOW 优先级，不实现 ActivityLog。

- [ ] **Step 1: 写出规则冲突 RED 测试**

创建 `RulesViewModelTest.kt`：

```kotlin
@Test
fun sameSourceTargetAllowAndBlockAreMarkedAsConflictButBothRemainVisible() = runTest {
    val repository = FakeRulesRepository(
        rules = listOf(
            ManagedPairRule(1L, "news", "store", RuleKind.ALLOW, true, "MANUAL", 10L),
            ManagedPairRule(2L, "news", "store", RuleKind.BLOCK, true, "FEEDBACK", 20L),
        ),
    )
    val viewModel = RulesViewModel(repository, StandardTestDispatcher(testScheduler))

    advanceUntilIdle()

    assertEquals(2, viewModel.uiState.value.rules.size)
    assertTrue(viewModel.uiState.value.rules.all { it.hasConflict })
}

@Test
fun disablingAllowDoesNotDisableBlock() = runTest {
    val repository = FakeRulesRepository(
        rules = listOf(
            ManagedPairRule(1L, "news", "store", RuleKind.ALLOW, true, "MANUAL", 10L),
            ManagedPairRule(2L, "news", "store", RuleKind.BLOCK, true, "FEEDBACK", 20L),
        ),
    )
    val viewModel = RulesViewModel(repository, StandardTestDispatcher(testScheduler))

    viewModel.setEnabled(1L, false)
    advanceUntilIdle()

    assertEquals(false, repository.rule(1L).enabled)
    assertEquals(true, repository.rule(2L).enabled)
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.rules.RulesViewModelTest" --no-daemon --console=plain
```

预期：编译失败，提示 RulesViewModel 和规则 UI state 不存在。

- [ ] **Step 3: 实现 RulesViewModel**

定义：

```kotlin
enum class RuleFilter { All, Allow, Block, Conflict }

data class RuleListItem(
    val rule: ManagedPairRule,
    val hasConflict: Boolean,
)

data class RulesUiState(
    val filter: RuleFilter,
    val rules: List<RuleListItem>,
    val loading: Boolean,
    val errorMessage: String?,
)
```

冲突判定只检查相同 sourcePackage 和 targetPackage 是否同时存在 ALLOW、BLOCK；无论其中任一规则是否启用，仍标记为有冲突，确保用户能发现遗留或暂停规则。筛选 Conflict 返回 `hasConflict = true` 的条目。`setEnabled`、`deleteRule` 调用 Task 3 Repository 接口，失败用 UI effect 提示。

RuleEditor 使用 `ManagedPairRule` 作为编辑模型。保存时：

- source、target 不能为空。
- 选择类型为 ALLOW 或 BLOCK。
- 同 kind 规则已存在时调用 `saveManagedRule` 更新它。
- 对方 kind 已存在时显示“存在允许/阻止冲突”，但仍允许保存。

- [ ] **Step 4: 写出规则页面 RED UI 测试**

在 `RulesScreenInstrumentedTest.kt` 添加：

```kotlin
@Test
fun conflictRowsShowBothRuleKindsAndSeparateControls() {
    composeRule.setContent {
        RulesScreen(
            state = RulesUiState(
                filter = RuleFilter.All,
                loading = false,
                errorMessage = null,
                rules = listOf(
                    RuleListItem(ManagedPairRule(1L, "news", "store", RuleKind.ALLOW, true, "MANUAL", 10L), true),
                    RuleListItem(ManagedPairRule(2L, "news", "store", RuleKind.BLOCK, true, "FEEDBACK", 20L), true),
                ),
            ),
            onFilterChanged = {},
            onSetEnabled = { _, _ -> },
            onDelete = {},
            onEdit = {},
            onAdd = {},
        )
    }

    composeRule.onNodeWithText("允许").assertIsDisplayed()
    composeRule.onNodeWithText("阻止").assertIsDisplayed()
    composeRule.onNodeWithText("存在允许/阻止冲突").assertIsDisplayed()
}
```

- [ ] **Step 5: 实现 RulesScreen、RuleEditor 和导航**

RulesScreen 使用 TabRow 或等价 segmented control 显示“全部”“允许”“阻止”“冲突”。每行稳定 key 使用 rule.id，展示 source、target、类型、启用开关、origin、更新时间和冲突标签。删除操作先显示明确的确认对话框，再调用 ViewModel。编辑入口导航到 `AppRoutes.ruleEditor(rule.id)`；新增入口导航到 `AppRoutes.ruleEditor(null)`。

RuleEditor 用来源和目标的 AppCatalog 展示名辅助选择，但只保存 packageName。进入编辑时通过 ruleId 读取当前规则；离开未保存编辑时显示确认。

- [ ] **Step 6: 运行 GREEN 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.rules.RulesViewModelTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：规则 ViewModel 测试和 AndroidTest 编译成功。

- [ ] **Step 7: 验收**

- 全部规则页包含启用和停用规则。
- 同 pair 的 ALLOW/BLOCK 同时可见且显示冲突。
- 每条规则可独立启停或删除，操作只影响其 ID。
- 同 kind 保存不产生重复行；不同 kind 保存不互相删除。
- UI 不改变 `RuleEvaluator` 的既有优先级。

- [ ] **Step 8: 提交**

```powershell
git add app/src/main/java/com/shakeguard/ui/rules/RulesViewModel.kt app/src/main/java/com/shakeguard/ui/rules/RulesScreen.kt app/src/main/java/com/shakeguard/ui/rules/RuleEditorScreen.kt app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt app/src/test/java/com/shakeguard/ui/rules/RulesViewModelTest.kt app/src/androidTest/java/com/shakeguard/ui/rules/RulesScreenInstrumentedTest.kt
git commit -m "feat: manage source target rules"
```

## 13. Task 11：ActivityLog、四种反馈与全量清空

**Phase:** 4

**Files:**

- Create: `app/src/main/java/com/shakeguard/ui/activity/ActivityEventUiModel.kt`
- Create: `app/src/main/java/com/shakeguard/ui/activity/ActivityLogViewModel.kt`
- Create: `app/src/main/java/com/shakeguard/ui/activity/ActivityLogScreen.kt`
- Modify: `app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt`
- Create: `app/src/test/java/com/shakeguard/ui/activity/ActivityLogViewModelTest.kt`
- Create: `app/src/androidTest/java/com/shakeguard/ui/activity/ActivityLogScreenInstrumentedTest.kt`

**Scope boundary:** 只使用已有 FeedbackHandler 和 Task 2 的事件清空接口；不改变通知 Receiver、FeedbackHandler 或事件 schema。

- [ ] **Step 1: 写出 ActivityLog RED ViewModel 测试**

创建 `ActivityLogViewModelTest.kt`：

```kotlin
@Test
fun blockEventExposesAllFourFeedbackCommandsAndObserveEventExposesNone() = runTest {
    val repository = FakeActivityRepository(
        listOf(
            ActivityEvent(7L, "news", "store", 100L, DecisionKind.BLOCK, null, ActionResult.RETURNED, null, 10L),
            ActivityEvent(8L, "news", "browser", 120L, DecisionKind.OBSERVE, null, ActionResult.NOT_REQUESTED, null, 20L),
        ),
    )
    val viewModel = ActivityLogViewModel(repository, FakeFeedbackHandler(), StandardTestDispatcher(testScheduler))

    advanceUntilIdle()

    assertEquals(4, viewModel.uiState.value.events.single { it.id == 7L }.feedbackActions.size)
    assertTrue(viewModel.uiState.value.events.single { it.id == 8L }.feedbackActions.isEmpty())
}

@Test
fun clearConfirmationDeletesOnlyEventsAndShowsEmptyState() = runTest {
    val repository = FakeActivityRepository(
        listOf(ActivityEvent(7L, "news", "store", 100L, DecisionKind.BLOCK, null, ActionResult.RETURNED, null, 10L)),
    )
    val viewModel = ActivityLogViewModel(repository, FakeFeedbackHandler(), StandardTestDispatcher(testScheduler))

    viewModel.requestClear()
    assertTrue(viewModel.uiState.value.clearConfirmationVisible)
    viewModel.confirmClear()
    advanceUntilIdle()

    assertEquals(1, repository.clearCalls)
    assertTrue(viewModel.uiState.value.events.isEmpty())
    assertTrue(viewModel.uiState.value.isEmpty)
}

@Test
fun feedbackNotFoundRefreshesRatherThanClaimingSuccess() = runTest {
    val feedback = FakeFeedbackHandler(result = FeedbackResult.NotFound(7L))
    val viewModel = ActivityLogViewModel(FakeActivityRepository(blockEventOnly()), feedback, StandardTestDispatcher(testScheduler))

    viewModel.applyFeedback(FeedbackCommand.AllowPair(7L))
    advanceUntilIdle()

    assertEquals(ActivityLogEffect.EventMissing(7L), viewModel.effects.first())
}
```

- [ ] **Step 2: 运行 RED 测试**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.activity.ActivityLogViewModelTest" --no-daemon --console=plain
```

预期：编译失败，提示 ActivityLogViewModel、UI model、effect 或构造器依赖不存在。

- [ ] **Step 3: 实现事件 UI model 和 ViewModel**

定义：

```kotlin
data class ActivityEventUiModel(
    val id: Long,
    val sourcePackage: String,
    val targetPackage: String,
    val decision: DecisionKind,
    val actionResult: ActionResult,
    val userFeedback: String?,
    val feedbackActions: List<FeedbackCommand>,
)

sealed interface ActivityLogEffect {
    data class Message(val text: String) : ActivityLogEffect
    data class EventMissing(val eventId: Long) : ActivityLogEffect
    data class Failure(val eventId: Long, val cause: Throwable) : ActivityLogEffect
}
```

ActivityLogViewModel 从 `observeRecentEvents(limit = 100)` 收集数据。只为 `DecisionKind.BLOCK` 且 id > 0 的事件生成 `AllowOnce`、`AllowPair`、`ConfirmAd`、`StopProtectingSource` 四个 command。`applyFeedback` 只能调用 `FeedbackHandler.handle(command)`：

- `Applied` 发出成功 Message，依靠 Room Flow 更新事件状态。
- `NotFound` 发出 `EventMissing`，不发成功消息。
- `Failed` 发出 Failure，保留当前列表。

`requestClear` 仅使 `clearConfirmationVisible = true`；`confirmClear` 调用 `clearAllEvents`，操作中禁止重复提交，成功后由事件 Flow 变空，失败时发出 Failure 且不清空内存列表。

- [ ] **Step 4: 写出 ActivityLogScreen RED UI 测试**

在 `ActivityLogScreenInstrumentedTest.kt` 添加：

```kotlin
@Test
fun clearRequiresConfirmationAndEmptyStateExplainsRulesAreKept() {
    var confirmed = false
    composeRule.setContent {
        ActivityLogScreen(
            state = ActivityLogUiState(
                events = emptyList(),
                loading = false,
                isEmpty = true,
                clearConfirmationVisible = true,
                clearing = false,
            ),
            onFeedback = {},
            onRequestClear = {},
            onConfirmClear = { confirmed = true },
            onDismissClear = {},
        )
    }

    composeRule.onNodeWithText("将删除全部跳转记录").assertIsDisplayed()
    composeRule.onNodeWithText("来源配置、时间窗和规则不会受到影响").assertIsDisplayed()
    composeRule.onNodeWithText("确认清空").performClick()
    assertTrue(confirmed)
}
```

- [ ] **Step 5: 实现 ActivityLogScreen 并接入路由**

ActivityLogScreen 使用 LazyColumn、事件 ID 作为 key。行展示来源到目标、决策、动作、时间和反馈标签；展开详情展示 event ID、两个 package、elapsedMs、matchedRuleId、userFeedback、createdAt。反馈操作只在 ViewModel 提供的 `feedbackActions` 非空时显示。用户已有反馈后选择不同反馈时先显示确认对话框。

清空按钮显示确认对话框，文案必须包含：

```text
将删除全部跳转记录。
来源配置、时间窗和规则不会受到影响。
此操作不可恢复。
```

空状态必须说明来源和规则仍然保留，并提供回到规则页的回调。

- [ ] **Step 6: 运行 GREEN 测试和完整 JVM 回归**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --tests "com.shakeguard.ui.activity.ActivityLogViewModelTest" --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：ActivityLog ViewModel、完整 JVM suite 和 AndroidTest 编译成功。

- [ ] **Step 7: 验收**

- 默认只观察最近 100 条，按时间倒序。
- 只有 BLOCK 事件显示四种反馈。
- 所有反馈经 FeedbackHandler；UI 不直接写 feedback、rule 或 source DAO。
- `NotFound` 与 `Failed` 均不会显示成功。
- 清空前必须确认；清空只删除事件，成功后进入空状态。
- 空状态明确说明来源、时间窗和规则仍存在。

- [ ] **Step 8: 提交**

```powershell
git add app/src/main/java/com/shakeguard/ui/activity/ActivityEventUiModel.kt app/src/main/java/com/shakeguard/ui/activity/ActivityLogViewModel.kt app/src/main/java/com/shakeguard/ui/activity/ActivityLogScreen.kt app/src/main/java/com/shakeguard/ui/navigation/AppNavHost.kt app/src/test/java/com/shakeguard/ui/activity/ActivityLogViewModelTest.kt app/src/androidTest/java/com/shakeguard/ui/activity/ActivityLogScreenInstrumentedTest.kt
git commit -m "feat: add activity log feedback UI"
```

## 14. Task 12：Phase 5 集成验证、性能检查与 README 学习文档

**Phase:** 5

**Files:**

- Create: `app/src/androidTest/java/com/shakeguard/ui/OperablePrototypeUiInstrumentedTest.kt`
- Modify: `README.md`

**Scope boundary:** 只增加端到端验证和学习文档；不在此任务引入新功能、数据库 schema 或权限。

- [ ] **Step 1: 写出 RED 集成 UI 测试**

创建 `OperablePrototypeUiInstrumentedTest.kt`：

```kotlin
@Test
fun userCanNavigateFromHomeToRulesAndActivityAndReturnToHome() {
    composeRule.setContent { ShakeGuardApp() }

    composeRule.onNodeWithText("首页").assertIsDisplayed()
    composeRule.onNodeWithText("规则").performClick()
    composeRule.onNodeWithText("活动").performClick()
    composeRule.onNodeWithText("首页").performClick()
    composeRule.onNodeWithText("首页页面").assertIsDisplayed()
}

@Test
fun notificationUnavailableDoesNotReplaceProtectionDisabledStatus() {
    composeRule.setContent {
        HomeScreen(
            state = HomeUiState(
                status = HomeStatus.ProtectionDisabled,
                serviceConnected = true,
                configuredSourceCount = 1,
                pausedSourceCount = 0,
                notificationCapability = NotificationCapability.PermissionDenied,
            ),
            onSetProtectionEnabled = {},
            onOpenAccessibilitySettings = {},
            onOpenNotificationSettings = {},
            onAddSource = {},
            onOpenActivity = {},
        )
    }

    composeRule.onNodeWithText("保护已关闭，服务仍保持连接").assertIsDisplayed()
    composeRule.onNodeWithText("通知提醒不可用").assertIsDisplayed()
}
```

- [ ] **Step 2: 运行 RED 测试编译**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --no-daemon --console=plain
```

预期：如果前序任务尚未完成，编译失败并指出缺失的 UI 类型；在本计划前序任务全部完成后，此步骤应跳过并直接执行下一步。

- [ ] **Step 3: 补齐测试可注入入口与完成 GREEN 测试**

如果集成测试无法构造生产依赖，给 `ShakeGuardApp` 增加仅测试使用的参数：

```kotlin
@Composable
fun ShakeGuardApp(
    startDestination: String = AppRoutes.HOME,
    dependencies: UiDependencies = rememberApplicationUiDependencies(),
)
```

`UiDependencies` 只聚合 ViewModel factory 和 system adapter，生产默认值从 ShakeGuardApplication 获取；不向 Composable 暴露 DAO、Room Entity 或 Android service。测试提供 fake dependencies，以稳定验证导航和状态。不要使用全局 mutable singleton 伪造状态。

- [ ] **Step 4: 运行完整构建、JVM、AndroidTest 与真实设备验证**

运行：

```powershell
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:compileDebugAndroidTestKotlin --rerun-tasks --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDebug --rerun-tasks --no-daemon --console=plain
.\gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：前三个命令必须成功。第四个命令在有可用 ADB 设备/模拟器时必须成功；没有设备时记录 ADB 错误、执行数为零和未验证的设备能力，不把它描述为通过。

在 Android 13+ 设备进行手工验证并记录结果：

1. 无障碍服务未授权：首页显示“需要系统授权”。
2. 完成授权并返回：首页刷新为无来源或保护中。
3. 关闭全局保护：服务保持启用，新的跳转不 Back、不产生 BLOCK event、不发通知。
4. 打开全局保护：下一次来源到目标跳转恢复保护。
5. 拒绝通知权限：保护和 ActivityLog 反馈仍可用。
6. 暂停来源：来源仍显示“已暂停”，时间窗和规则保持；恢复后重新生效。
7. 同 pair ALLOW/BLOCK：规则页显示冲突，两条可独立操作。
8. 清空 ActivityLog：确认后列表为空，来源和规则仍存在。

- [ ] **Step 5: 更新 README 为可学习的交付文档**

在 README 添加以下明确章节：

- “可操作 UI”：三项导航、来源/规则/活动入口和四种反馈。
- “权限与降级”：无障碍服务必需、通知可选、通知拒绝不影响保护。
- “全局保护开关”：服务保持绑定，关闭时没有 Back、BLOCK event 或通知，打开后立即恢复。
- “本地隐私边界”：仅保存包名、规则和事件元数据；不读取正文、输入、通知正文或 URL；不上传网络。
- “数据保留”：暂停来源保留配置和规则，清空只删除 jump_events。
- “技术复习”：Compose Navigation、StateFlow、Room Flow、DataStore、ProtectionGate、FeedbackHandler、单元测试和 instrumentation 的职责。
- “验证结果”：记录实际 Gradle 命令、测试数、设备型号/API、通过/失败、已知限制和解决方法。

README 中每项结果必须来自本任务 Step 4 的真实输出。设备不可用时写明“未执行”，包括具体阻塞原因。

- [ ] **Step 6: 验收**

- 完整 JVM suite、AndroidTest 编译和 debug assemble 成功。
- 有可用设备时，connected AndroidTest 成功；无设备时 README 精确说明限制。
- README 用中文解释技术、问题和解决方式，普通用户和 AI 专业学生均能理解。
- README 不夸大无 root 能力，不承诺跳转前绝对拦截。
- UI 仍只面向 Android，仍保持本地隐私边界。

- [ ] **Step 7: 提交**

```powershell
git add app/src/androidTest/java/com/shakeguard/ui/OperablePrototypeUiInstrumentedTest.kt README.md
git commit -m "docs: verify operable prototype UI"
```

## 15. 规格覆盖自检

| 规格要求 | 实施任务 |
| --- | --- |
| Android 29+、普通用户低门槛和本地隐私边界 | Task 7、Task 8、Task 12 |
| 全局保护关闭时服务保持绑定且无副作用 | Task 4、Task 5、Task 7、Task 12 |
| 首页/规则/活动三项底部导航 | Task 6 |
| 无障碍设置跳转与返回刷新 | Task 7 |
| 通知作为可选能力 | Task 7、Task 12 |
| 暂停来源继续展示、保留配置、可恢复 | Task 2、Task 8、Task 9 |
| 每来源时间窗和来源级阻止 | Task 9 |
| AppCatalog 只运行时解析 label/icon | Task 8 |
| ALLOW/BLOCK 冲突保留和独立操作 | Task 3、Task 10 |
| 最近 100 条 ActivityLog | Task 2、Task 11 |
| 四种反馈统一经过 FeedbackHandler | Task 11 |
| 清空只删除事件、确认后空状态 | Task 2、Task 11 |
| 性能、设备验证和 README 学习文档 | Task 8、Task 11、Task 12 |

## 16. 计划自检结论

- 每个 Phase 0-5 的功能都映射到至少一个独立任务。
- 所有任务给出精确文件路径、RED 测试代码、GREEN 命令、验收条件、范围边界和建议 commit。
- 统一类型段定义了 `ManagedSource`、`ManagedPairRule`、`ActivityEvent`、ProtectionGate 和 Repository 方法，后续任务沿用这些名称。
- 禁止词扫描通过，任务描述没有省略实现步骤或跨任务指代。
- 计划没有要求修改既有反馈计划文件；执行 Task 12 前不修改 README，Task 12 的 README 更新仅记录真实验证结果。
- 计划不引入网络、页面正文读取、Intent 重放或跨平台范围，保持已批准的 Android-only、本地隐私边界。
