# ShakeGuard Feedback and Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local, reversible feedback loop where users can allow the next exact jump once, persist allow/block pair rules, disable a protected source, and invoke the same behavior from notifications or ActivityLog.

**Architecture:** A thread-safe in-memory `OneTimeAllowanceStore` sits before `RuleEvaluator`. A pure Kotlin `FeedbackHandler` validates an event and delegates all durable changes to `FeedbackRepository`; both notification actions and future ActivityLog actions call this handler. A process-wide `ShakeGuardApplication` graph shares the same Room database, repository, allowance store, handler, and notification manager with the accessibility service and receiver.

**Tech Stack:** Kotlin, coroutines `Mutex`, Android Room 2.7, Android notifications/PendingIntent, BroadcastReceiver, JUnit 4, AndroidX instrumentation.

---

## File Map

- Create `app/src/main/java/com/shakeguard/feedback/OneTimeAllowanceStore.kt`: 30-second exact-pair token storage.
- Create `app/src/main/java/com/shakeguard/feedback/FeedbackModels.kt`: commands, target, result, and repository interface.
- Create `app/src/main/java/com/shakeguard/feedback/FeedbackHandler.kt`: shared feedback use case.
- Create `app/src/main/java/com/shakeguard/ShakeGuardApplication.kt`: process-wide dependency graph.
- Create `app/src/main/java/com/shakeguard/notifications/ProtectionNotificationManager.kt`: channel, permission check, and actions.
- Create `app/src/main/java/com/shakeguard/notifications/FeedbackReceiver.kt`: explicit non-exported action receiver.
- Modify `app/src/main/java/com/shakeguard/protection/ProtectionCoordinator.kt`: consume one-time allowance and return persisted event ID.
- Modify `app/src/main/java/com/shakeguard/data/Daos.kt`: event lookup/update, pair lookup/update, source disable, first-block count.
- Create `app/src/main/java/com/shakeguard/data/RoomFeedbackRepository.kt`: transactional feedback writes and idempotent rule upsert.
- Modify `app/src/main/java/com/shakeguard/data/RoomProtectionEventRecorder.kt`: return inserted event ID.
- Modify `app/src/main/java/com/shakeguard/accessibility/ShakeGuardAccessibilityService.kt`: use app graph and publish first-block notification.
- Modify `app/src/main/AndroidManifest.xml`: application class, notification permission, and receiver.
- Create focused JVM and instrumentation tests under existing `app/src/test` and `app/src/androidTest` trees.
- Modify `README.md`: document semantics, permission fallback, tests, and limitations.

## Task 1: Implement the one-time allowance token

**Files:**
- Create: `app/src/main/java/com/shakeguard/feedback/OneTimeAllowanceStore.kt`
- Create: `app/src/test/java/com/shakeguard/feedback/OneTimeAllowanceStoreTest.kt`

- [x] **Step 1: Write the first failing token test**

```kotlin
@Test
fun exactPairIsConsumedOnlyOnceWithinThirtySeconds() = runBlocking {
    val clock = FakeClock(1_000L)
    val store = OneTimeAllowanceStore(clock)
    store.grant("news", "store")

    clock.advanceBy(30_000L)
    assertTrue(store.consume("news", "store"))
    assertFalse(store.consume("news", "store"))
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.shakeguard.feedback.OneTimeAllowanceStoreTest" --no-daemon --console=plain
```

Expected: compilation fails because `OneTimeAllowanceStore` does not exist.

- [x] **Step 3: Implement an atomic monotonic token store**

```kotlin
class OneTimeAllowanceStore(
    private val clock: MonotonicClock,
    private val ttlMs: Long = 30_000L,
) {
    private data class Key(val source: String, val target: String)
    private data class Token(val createdAt: Long, val expiresAt: Long)
    private val mutex = Mutex()
    private val tokens = mutableMapOf<Key, Token>()

    suspend fun grant(source: String, target: String) = mutex.withLock {
        val now = clock.elapsedRealtime()
        tokens[Key(source, target)] = Token(now, now + ttlMs)
    }

    suspend fun consume(source: String, target: String): Boolean = mutex.withLock {
        val key = Key(source, target)
        val token = tokens[key] ?: return@withLock false
        val now = clock.elapsedRealtime()
        val valid = now >= token.createdAt && now <= token.expiresAt
        if (valid || now > token.expiresAt || now < token.createdAt) tokens.remove(key)
        valid
    }

    suspend fun revoke(source: String, target: String) = mutex.withLock {
        tokens.remove(Key(source, target))
    }
}
```

- [x] **Step 4: Add exact mismatch, expiry, boundary, and rollback tests**

Tests must independently assert that a different source/target cannot consume the token, `30_000` ms is valid, `30_001` ms is expired, a clock value below `createdAt` invalidates the token, and `revoke` removes an unconsumed token.

- [x] **Step 5: Run the focused test and verify GREEN**

Expected: all `OneTimeAllowanceStoreTest` cases pass.

## Task 2: Make one-time allowance precede rule evaluation

**Files:**
- Modify: `app/src/main/java/com/shakeguard/protection/ProtectionCoordinator.kt`
- Modify: `app/src/test/java/com/shakeguard/protection/ProtectionCoordinatorTest.kt`

- [x] **Step 1: Write a failing coordinator precedence test**

```kotlin
@Test
fun oneTimeAllowanceOverridesSourceBlockAndIsConsumed() = runBlocking {
    allowanceStore.grant("news", "store")
    coordinator.onForeground("news")

    val allowed = coordinator.onForeground("store")
    assertEquals(DecisionKind.ALLOW, allowed?.decision?.kind)
    assertEquals("one-time allowance", allowed?.decision?.reason)
    assertEquals(0, back.callCount)

    coordinator.onForeground("news")
    val blockedAgain = coordinator.onForeground("store")
    assertEquals(DecisionKind.BLOCK, blockedAgain?.decision?.kind)
}
```

- [x] **Step 2: Verify RED**

Expected: the first transition is blocked because the coordinator does not consume allowances.

- [x] **Step 3: Inject and consume `OneTimeAllowanceStore` before `RuleEvaluator`**

Add `allowanceStore` to the constructor. After obtaining a `ForegroundTransition`, call `consume(sourcePackage, targetPackage)`. On success, close the transition in `SessionTracker` and return:

```kotlin
ProtectionOutcome(
    Decision(DecisionKind.ALLOW, "one-time allowance"),
    ActionResult.NOT_REQUESTED,
    eventId = null,
)
```

Do not call Back and do not persist a new interception record.

- [x] **Step 4: Run all coordinator tests**

Expected: old block/failure/window tests and the new allowance test all pass.

## Task 3: Return event IDs from protection recording

**Files:**
- Modify: `app/src/main/java/com/shakeguard/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/shakeguard/data/Daos.kt`
- Create: `app/src/main/java/com/shakeguard/data/RoomFeedbackRepository.kt`
- Modify: `app/src/main/java/com/shakeguard/data/RoomProtectionEventRecorder.kt`
- Modify: corresponding JVM fake implementations and tests.

- [x] **Step 1: Write a failing event-ID test**

Make the fake recorder return `42L` and assert `blockedOutcome.eventId == 42L`.

- [x] **Step 2: Change the interfaces**

```kotlin
fun interface ProtectionEventRecorder {
    suspend fun record(record: ProtectionRecord): Long
}

data class ProtectionOutcome(
    val decision: Decision,
    val actionResult: ActionResult,
    val eventId: Long?,
)
```

Change `JumpEventDao.insert` and `RuleRepository.recordEvent` to return `Long`. `RoomProtectionEventRecorder.record` returns that ID. `ProtectionCoordinator` stores the ID for `BLOCK` and `OBSERVE`, otherwise `null`.

- [x] **Step 3: Update fakes and run all JVM tests**

Expected: all JVM suites pass without changing previous decision behavior.

## Task 4: Add feedback DAO operations and Repository contract

**Files:**
- Create: `app/src/main/java/com/shakeguard/feedback/FeedbackModels.kt`
- Modify: `app/src/main/java/com/shakeguard/data/Daos.kt`
- Modify: `app/src/main/java/com/shakeguard/data/Repositories.kt`
- Modify: `app/src/test/java/com/shakeguard/data/RuleRepositoryTest.kt`
- Modify: `app/src/androidTest/java/com/shakeguard/data/RoomDaoInstrumentedTest.kt`

- [x] **Step 1: Define the feedback contract**

```kotlin
data class FeedbackTarget(val eventId: Long, val sourcePackage: String, val targetPackage: String)

interface FeedbackRepository {
    suspend fun findFeedbackTarget(eventId: Long): FeedbackTarget?
    suspend fun markAllowOnce(target: FeedbackTarget)
    suspend fun applyAllowPair(target: FeedbackTarget, updatedAt: Long): Long
    suspend fun applyConfirmAd(target: FeedbackTarget, updatedAt: Long): Long
    suspend fun applyStopProtecting(target: FeedbackTarget, updatedAt: Long): Boolean
    suspend fun countBlockedEvents(source: String, target: String): Int
}
```

- [x] **Step 2: Write failing Repository tests**

Test event lookup, idempotent `ALLOW`/`BLOCK` upsert, event feedback update, source disable preserving window/createdAt, and blocked-event counting.

- [x] **Step 3: Add exact DAO queries**

```kotlin
@Query("SELECT * FROM jump_events WHERE id = :id LIMIT 1")
suspend fun find(id: Long): JumpEventEntity?

@Query("UPDATE jump_events SET userFeedback = :feedback WHERE id = :id")
suspend fun updateFeedback(id: Long, feedback: String): Int

@Query("SELECT * FROM pair_rules WHERE sourcePackage=:source AND targetPackage=:target AND kind=:kind LIMIT 1")
suspend fun findByKind(source: String, target: String, kind: String): PairRuleEntity?

@Update
suspend fun update(rule: PairRuleEntity)

@Query("UPDATE protected_sources SET enabled=0, updatedAt=:updatedAt WHERE packageName=:packageName")
suspend fun disable(packageName: String, updatedAt: Long): Int

@Query("SELECT COUNT(*) FROM jump_events WHERE sourcePackage=:source AND targetPackage=:target AND decision='BLOCK'")
suspend fun countBlocks(source: String, target: String): Int
```

- [x] **Step 4: Implement atomic, idempotent Repository operations**

`RoomFeedbackRepository` receives `AppDatabase` and wraps every multi-table feedback operation in `database.withTransaction`. Use `findByKind`; insert when absent, otherwise update the existing row with `enabled=true`, `origin="FEEDBACK"`, and the new timestamp. In the same transaction, update the event feedback. Return the stable existing/new ID. `applyStopProtecting` updates the event and source in one transaction and returns `false` without changing the event when the source row is absent. `markAllowOnce` updates only the event after the in-memory token has been granted.

- [x] **Step 5: Run JVM tests, then Android instrumentation**

Expected: fake DAO tests and real Room SQL tests both pass. No schema version bump is needed because only queries change.

## Task 5: Implement the shared FeedbackHandler

**Files:**
- Create: `app/src/main/java/com/shakeguard/feedback/FeedbackHandler.kt`
- Create: `app/src/test/java/com/shakeguard/feedback/FeedbackHandlerTest.kt`

- [x] **Step 1: Define commands and results in `FeedbackModels.kt`**

```kotlin
sealed interface FeedbackCommand {
    val eventId: Long
    data class AllowOnce(override val eventId: Long) : FeedbackCommand
    data class AllowPair(override val eventId: Long) : FeedbackCommand
    data class ConfirmAd(override val eventId: Long) : FeedbackCommand
    data class StopProtectingSource(override val eventId: Long) : FeedbackCommand
}

sealed interface FeedbackResult {
    data class Applied(val command: FeedbackCommand) : FeedbackResult
    data class NotFound(val eventId: Long) : FeedbackResult
    data class Failed(val eventId: Long, val cause: Throwable) : FeedbackResult
}
```

- [x] **Step 2: Write one failing test per command**

Assert exact pair grant for `AllowOnce`, idempotent ALLOW upsert, BLOCK upsert plus `AD` event update, and source disable. Add missing-event and Repository-exception tests.

- [x] **Step 3: Implement a mutex-serialized handler**

Load `FeedbackTarget` first. Inside one handler mutex, grant the one-time token plus call `markAllowOnce`, or call one of the Repository's atomic persistent methods. Catch Repository exceptions and return `Failed`; Room transactions prevent partially written persistent rules/events. If `markAllowOnce` fails after granting the token, consume/invalidate that exact token before returning `Failed` so no unrecorded allowance remains.

- [x] **Step 4: Run `FeedbackHandlerTest` and full JVM regression**

Expected: every command is deterministic and repeated persistent commands reuse the same rule row.

## Task 6: Introduce a process-wide application graph

**Files:**
- Create: `app/src/main/java/com/shakeguard/ShakeGuardApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/shakeguard/accessibility/ShakeGuardAccessibilityService.kt`

- [x] **Step 1: Create one shared graph**

`ShakeGuardApplication` lazily creates `AppDatabase` with `MIGRATION_1_2`, `RuleRepository`, `OneTimeAllowanceStore(SystemMonotonicClock)`, and `FeedbackHandler`. Expose typed read-only properties; do not use a service locator with string keys.

- [x] **Step 2: Register the Application**

Set `android:name=".ShakeGuardApplication"` on `<application>`.

- [x] **Step 3: Rewire the accessibility service**

Remove its private database construction. Read the application graph, pass the shared allowance store to `ProtectionCoordinator`, and leave lifecycle ownership of Room with the Application. The service still owns and closes only its coroutine dispatcher.

- [x] **Step 4: Run JVM tests and `assembleDebug`**

Expected: application and service compile; existing service smoke configuration remains unchanged.

## Task 7: Build notifications and explicit feedback receiver

**Files:**
- Create: `app/src/main/java/com/shakeguard/notifications/ProtectionNotificationManager.kt`
- Create: `app/src/main/java/com/shakeguard/notifications/FeedbackReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: notification strings and small icon resource.

- [x] **Step 1: Add manifest declarations**

Add `android.permission.POST_NOTIFICATIONS` and a non-exported receiver:

```xml
<receiver
    android:name=".notifications.FeedbackReceiver"
    android:exported="false" />
```

- [x] **Step 2: Implement permission-aware notification publishing**

Create channel `protection_feedback`. On Android 33+, return `false` without posting when `POST_NOTIFICATIONS` is denied. Build a notification using source/target labels and four immutable explicit PendingIntents. Use a unique request code derived from event ID and action ordinal.

- [x] **Step 3: Implement receiver command mapping**

Map only internal action constants to the four `FeedbackCommand` types, reject missing/non-positive event IDs, and call the application graph handler inside `goAsync()` with a bounded coroutine scope. Always call `PendingResult.finish()` in `finally`.

- [x] **Step 4: Add Android instrumentation tests**

Verify channel creation, immutable PendingIntent construction, unique request codes, action-to-command mapping, malformed intent rejection, and permission denial returning `false` without an exception.

## Task 8: Publish only the first blocked-pair notification

**Files:**
- Modify: `app/src/main/java/com/shakeguard/accessibility/ShakeGuardAccessibilityService.kt`
- Modify: `app/src/main/java/com/shakeguard/data/Repositories.kt`
- Modify: coordinator/service tests.

- [x] **Step 1: Write a failing first-block test**

Given a `BLOCK` outcome with an event ID, assert notification publishing occurs when `countBlockedEvents(source,target) == 1` and not when the count is greater than one.

- [x] **Step 2: Wire notification publication**

After `coordinator.onForeground` returns a blocked outcome with an event ID, query the count and publish only for the first blocked event. Notification failure or missing permission must not alter the protection decision or throw from the service event loop.

- [x] **Step 3: Run JVM regression and build androidTest APK**

Expected: protection remains successful regardless of notification availability.

## Task 9: Final verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-10-feedback-notification.md` checkbox states during execution.

- [x] **Step 1: Run all JVM tests**

```powershell
$env:JAVA_HOME = "G:\Java7\JDK17\jdk-17.0.20.8-hotspot"
$env:ANDROID_SDK_ROOT = "F:\Andriod-Studio\Sdk"
$env:GRADLE_USER_HOME = "C:\Users\zero\.gradle"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

- [ ] **Step 2: Connect the phone and run instrumentation tests**

```powershell
$env:ANDROID_USER_HOME = "C:\Users\zero\.android"
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
```

If OriginOS update installation waits, unlock the device, allow USB installation, install both APKs with `adb install --no-streaming -r -t`, then run:

```powershell
adb shell am instrument -w -r com.shakeguard.test/androidx.test.runner.AndroidJUnitRunner
```

- [ ] **Step 3: Verify notification permission denial**

On Android 16, revoke notification permission, trigger the instrumentation notification test, and confirm protection/feedback Repository tests still pass. Restore the original permission state afterward.

- [x] **Step 4: Update README**

Document the 30-second exact one-shot semantics, persistent actions, notification fallback, test counts, Android version, OEM installation issue, and the fact that no feedback leaves the device.

### Task 9 verification record

- Current HEAD `0eb66165796701fa5f0e25eef6211fae427c74d5` fresh JVM: 84 tests, all failures/errors/skipped equal to 0.
- `compileDebugAndroidTestKotlin` succeeded; `assembleDebug` succeeded with the repository-external Android user home `F:\Codex-app\shakeguard-task9-android-home` after the default debug keystore lock returned `AccessDeniedException`.
- `connectedDebugAndroidTest` reached APK packaging but stopped with `Cannot mkdir '\.android': Permission denied` / `Could not create ADB Bridge`; device execution was 0 cases. No notification permission revoke/restore test was possible without a usable device.
- README was rewritten as UTF-8 and records implemented behavior separately from the remaining product work.

## Commit Note

The current `F:\Codex-app\ShakeGuard` directory is not a Git repository, so execution cannot create the per-task commits recommended by this plan. If the project is later placed in `https://github.com/JiangZi0721/small-app.git`, use focused commit messages in task order: `feat: add one-time allowance`, `feat: persist local feedback`, `feat: add protection feedback notifications`, and `docs: document feedback workflow`.
