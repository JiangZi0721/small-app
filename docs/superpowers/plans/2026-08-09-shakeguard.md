# ShakeGuard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android 10+ no-root prototype that detects suspicious cross-app launches during a user-configured startup window and returns to the protected source app, with local rules, feedback, and audit history.

**Architecture:** A Kotlin Android app keeps UI, persistence, protection logic, and accessibility event adaptation in separate modules. `AccessibilityService` emits package transition events; a pure `RuleEvaluator` decides `IGNORE`, `ALLOW`, `BLOCK`, or `OBSERVE`; `BackActionExecutor` performs one system back action and persists the result. All state is local.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose, Material 3, Android `AccessibilityService`, Room, DataStore Preferences, Coroutines/Flow, AndroidX Navigation, JUnit, AndroidX test, and a small test-source/test-target APK pair.

---

## File Map

- Create: `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/shakeguard/...` grouped as `ui`, `accessibility`, `protection`, `data`, and `notifications`
- Create: `app/src/test/java/com/shakeguard/protection/RuleEvaluatorTest.kt`, `SessionTrackerTest.kt`, and `data/RuleRepositoryTest.kt`
- Create: `test-fixtures/source-app` and `test-fixtures/target-app` minimal Android modules for cross-app instrumentation
- Modify: `README.md` only after implementation to document build, permissions, test fixtures, and observed device behavior

### Task 1: Scaffold the Android project

**Files:** project Gradle files above, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/shakeguard/MainActivity.kt`.

- [ ] Create a Kotlin Android application with namespace `com.shakeguard`, `minSdk = 29`, `targetSdk` equal to the installed stable SDK, and Compose enabled.
- [ ] Add only the dependencies required by the architecture: Compose BOM/Material 3, Navigation Compose, Lifecycle ViewModel Compose, Room runtime/compiler, DataStore Preferences, Coroutines, and AndroidX test.
- [ ] Add a manifest service declaration with `android.permission.BIND_ACCESSIBILITY_SERVICE`, `android:exported="true"`, `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, and a resource reference to `res/xml/accessibility_service_config.xml`.
- [ ] Add a launcher activity and verify a blank Compose screen builds with `./gradlew :app:assembleDebug`.
- [ ] Commit: `chore: scaffold ShakeGuard Android app`.

### Task 2: Define domain types and write failing rule tests

**Files:** Create `protection/Domain.kt`, `protection/RuleEvaluator.kt`, tests listed in the file map.

- [ ] Define immutable types: `ProtectedSource(packageName: String, enabled: Boolean, windowMs: Long)`, `PairRule(sourcePackage: String, targetPackage: String, kind: RuleKind)`, `ForegroundTransition(sourcePackage: String, targetPackage: String, elapsedMs: Long, isSamePackage: Boolean)`, and `Decision(kind: DecisionKind, ruleId: Long? = null, reason: String)`.
- [ ] Define `RuleKind { BLOCK, ALLOW }` and `DecisionKind { IGNORE, ALLOW, BLOCK, OBSERVE }`.
- [ ] Write tests before implementation for: system package ignore; same-package ignore; no source configuration; exact window boundary inclusive; outside-window no-op; allow rule before source block; source block without pair rule; pair block for a non-source-level configuration; unmatched transition as observe; duplicate event suppression; recovery transition not reopening a session.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests 'com.shakeguard.protection.*'` and verify the new tests fail because `RuleEvaluator` and `SessionTracker` have no behavior.
- [ ] Commit: `test: define protection rule behavior`.

### Task 3: Implement pure protection logic

**Files:** Modify `protection/RuleEvaluator.kt`, create `protection/SessionTracker.kt`, `protection/BackActionExecutor.kt` interface, and `protection/Clock.kt`.

- [ ] Implement `RuleEvaluator.evaluate(transition, source, pairRules, exclusions): Decision` as a side-effect-free function with the exact precedence from the design: exclusions, no source/window, allow, source block, pair block, observe.
- [ ] Use an injected `Clock.elapsedRealtime(): Long`; never use wall-clock time for window comparisons.
- [ ] Implement `SessionTracker.onForeground(packageName, now): SessionState` with `NORMAL`, `PROTECTED`, and `RECOVERING` states. A transition from `RECOVERING` back to the expected source closes the session without restarting the window.
- [ ] Implement an in-memory 300 ms deduplicator keyed by source and target package. Return failures must be represented as `RETURN_FAILED`, and the executor interface must expose only `suspend fun goBack(): Boolean`.
- [ ] Run the same unit test command and verify all domain tests pass.
- [ ] Commit: `feat: implement deterministic protection engine`.

### Task 4: Add local persistence

**Files:** Create `data/AppDatabase.kt`, `data/Entities.kt`, `data/Daos.kt`, `data/Repositories.kt`, `data/SettingsStore.kt`, and `data/Converters.kt`.

- [ ] Map the design types to Room entities: `ProtectedSourceEntity`, `PairRuleEntity`, and `JumpEventEntity`; use package names as stable identifiers and store enum values as strings via a converter.
- [ ] Provide DAO methods for enabled sources, pair lookup by source/target, event insertion, 30-day event cleanup, and observing recent events as `Flow<List<JumpEventEntity>>`.
- [ ] Use DataStore keys `protection_enabled`, `default_window_ms`, and `notifications_enabled` with typed repository methods.
- [ ] Write repository tests using an in-memory Room database for insert/update/delete, feedback-to-rule conversion, event cleanup, and corrupted/missing rule references.
- [ ] Run `./gradlew :app:testDebugUnitTest` and commit: `feat: persist local sources rules and events`.

### Task 5: Integrate AccessibilityService

**Files:** Create `accessibility/ShakeGuardAccessibilityService.kt`, `accessibility/AccessibilityEventAdapter.kt`, `accessibility/Exclusions.kt`, and `app/src/main/res/xml/accessibility_service_config.xml`.

- [ ] Configure the service for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`, `feedbackType="feedbackGeneric"`, `notificationTimeout="100"`, and `canRetrieveWindowContent="false"`.
- [ ] Convert events to `ForegroundTransition` using only `event.packageName`, event class name, and an injected monotonic clock. Do not call `rootInActiveWindow`, inspect text, or read input fields.
- [ ] Exclude the ShakeGuard package, launcher packages discovered from `Intent.ACTION_MAIN/CATEGORY_HOME`, input methods, permission controller, System UI, and same-package Activity transitions.
- [ ] Inject a `ProtectionCoordinator` that loads repositories, feeds `SessionTracker` and `RuleEvaluator`, calls `BackActionExecutor` once for `BLOCK`, then persists `JumpEventEntity`.
- [ ] Add service lifecycle logging guarded by a debug flag; release builds must not log package transitions by default.
- [ ] Run a debug build and a service-start smoke test on an Android 10+ emulator; commit: `feat: connect accessibility protection service`.

### Task 6: Build feedback and notification flow

**Files:** Create `notifications/ProtectionNotificationManager.kt`, `notifications/FeedbackReceiver.kt`, `ui/feedback/FeedbackViewModel.kt`, and notification resources.

- [ ] On the first `BLOCK` for a source/target pair, post a notification containing source and target app labels and actions `ALLOW_ONCE`, `ALLOW_PAIR`, `CONFIRM_AD`.
- [ ] `ALLOW_ONCE` writes an in-memory event override; `ALLOW_PAIR` persists an `ALLOW` pair rule; `CONFIRM_AD` persists a `BLOCK` pair rule and marks the event feedback as `AD`.
- [ ] When notification permission is denied on Android 13+, expose the same actions from ActivityLog and never treat missing notification permission as a protection failure.
- [ ] Add tests for every action, idempotent repeated feedback, and feedback precedence over source block.
- [ ] Commit: `feat: add reversible local feedback loop`.

### Task 7: Implement Compose screens and onboarding

**Files:** Create `ui/AppNavHost.kt`, `ui/home/HomeScreen.kt`, `ui/apps/ProtectedAppsScreen.kt`, `ui/rules/RulesScreen.kt`, `ui/activity/ActivityLogScreen.kt`, `ui/onboarding/PermissionGuideScreen.kt`, and ViewModels for each.

- [ ] Implement the approved combined home layout: service status, protected source count, recent blocked events, and actions to manage apps/rules.
- [ ] Add the four-step onboarding flow: minimal-data explanation, `Settings.ACTION_ACCESSIBILITY_SETTINGS`, return-state check, source app selection/time-window editing, and optional notification permission.
- [ ] Use `PackageManager` to display installed launchable user apps; do not persist labels/icons as rule identifiers.
- [ ] Add per-source time-window choices with a default of 5 seconds and an explicit disable option; all values must flow through DataStore/Room repositories.
- [ ] Add ActivityLog actions for “still open”, “allow this pair”, “allow once”, and “this is an ad”.
- [ ] Run Compose previews and `./gradlew :app:testDebugUnitTest`; commit: `feat: add onboarding and rule management UI`.

### Task 8: Add cross-app instrumentation fixtures and verification

**Files:** Create `test-fixtures/source-app`, `test-fixtures/target-app`, `app/src/androidTest/.../ProtectionFlowTest.kt`, and update README.

- [ ] The source fixture launches a target fixture after a configurable delay; the target fixture exposes a visible marker and finishes when it receives Back.
- [ ] Instrumentation test enables a protected source fixture, launches it, waits for a target transition within the window, and asserts that the target is no longer foreground after one Back action.
- [ ] Add tests for outside-window transitions, source-level block, pair allow exception, unknown target, duplicate events, and service-disabled degraded state.
- [ ] Run `./gradlew testDebugUnitTest connectedDebugAndroidTest assembleDebug` on an Android 10+ emulator. Record device model, API level, pass/fail counts, and any OEM deviations in README.
- [ ] Commit: `test: verify cross-app interception prototype`.

### Task 9: Documentation and release checklist

**Files:** Modify `README.md`; create `docs/privacy.md`, `docs/testing-matrix.md`, and `docs/troubleshooting.md`.

- [ ] Document exact build prerequisites, Gradle commands, emulator setup, permission steps, fixture APK installation, and how to clear local data.
- [ ] Explain why root, VPN, and complete URL capture are intentionally absent, with links to the Android API limitations that shaped the design.
- [ ] Document privacy behavior, data retention, notification denial behavior, OEM battery restrictions, and the Google Play accessibility disclosure requirement.
- [ ] Run `rg -n "TODO|TBD|FIXME|待定" ShakeGuard` and resolve every match before calling the prototype complete.
- [ ] If Git metadata is added later, commit documentation separately as `docs: document ShakeGuard prototype and verification`.

## Verification Summary

The implementation is complete only after unit, instrumentation, build, and documentation checks pass. A successful build alone is insufficient because the central behavior depends on real cross-package accessibility events and OEM service behavior.
