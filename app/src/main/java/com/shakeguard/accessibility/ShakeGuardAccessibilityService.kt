package com.shakeguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.shakeguard.ShakeGuardApplication
import com.shakeguard.data.RoomProtectionEventRecorder
import com.shakeguard.data.RuleRepository
import com.shakeguard.feedback.FeedbackTarget
import com.shakeguard.protection.BackActionExecutor
import com.shakeguard.protection.ProtectionCoordinator
import com.shakeguard.protection.ProtectionOutcome
import com.shakeguard.protection.ProtectionGateState
import com.shakeguard.protection.RuleEvaluator
import com.shakeguard.protection.SessionTracker
import com.shakeguard.protection.SystemMonotonicClock
import com.shakeguard.feedback.OneTimeAllowanceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class ShakeGuardAccessibilityService : AccessibilityService() {
    private val eventAdapter = AccessibilityEventAdapter()
    private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + eventDispatcher)
    private val foregroundPackage = AtomicReference<String?>(null)
    private lateinit var coordinator: ProtectionCoordinator
    private lateinit var applicationGraph: ShakeGuardApplication

    override fun onServiceConnected() {
        super.onServiceConnected()
        applicationGraph = applicationContext as ShakeGuardApplication
        coordinator = createProtectionCoordinator(
            ruleRepository = applicationGraph.ruleRepository,
            allowanceStore = applicationGraph.allowanceStore,
            excludedPackages = Exclusions.discover(this),
            backAction = BackActionExecutor { sourcePackage, targetPackage ->
                executeBackWithRetry(
                    sourcePackage = sourcePackage,
                    targetPackage = targetPackage,
                    currentPackage = foregroundPackage::get,
                    performBack = {
                        withContext(Dispatchers.Main) { performGlobalAction(GLOBAL_ACTION_BACK) }
                    },
                    launchSource = {
                        withContext(Dispatchers.Main) {
                            if (sourcePackage == targetPackage) {
                                false
                            } else {
                                try {
                                    val launchIntent = packageManager
                                        .getLaunchIntentForPackage(sourcePackage)
                                        ?: return@withContext false
                                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    startActivity(launchIntent)
                                    true
                                } catch (_: RuntimeException) {
                                    false
                                }
                            }
                        }
                    },
                )
            },
        )
        debugLog("service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::coordinator.isInitialized) return
        val foreground = eventAdapter.adapt(event) ?: return
        foregroundPackage.set(foreground.packageName)
        serviceScope.launch {
            handleForegroundForProtection(
                packageName = foreground.packageName,
                gateState = applicationGraph.protectionGate.state.value,
                clearSession = coordinator::clearProtectionSession,
                evaluate = coordinator::onForeground,
                publishFirstBlock = { outcome ->
                    publishFirstBlockedNotification(
                        outcome = outcome,
                        findTarget = applicationGraph.ruleRepository::findFeedbackTarget,
                        countBlocked = applicationGraph.ruleRepository::countBlockedEvents,
                        publish = applicationGraph.protectionNotificationManager::publish,
                    )
                },
            )
        }
    }

    override fun onInterrupt() {
        debugLog("service interrupted")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        eventDispatcher.close()
        debugLog("service destroyed")
        super.onDestroy()
    }

    private fun debugLog(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TAG, message)
        }
    }

    private companion object {
        const val TAG = "ShakeGuardService"
    }
}

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

internal suspend fun executeBackWithRetry(
    sourcePackage: String,
    targetPackage: String,
    currentPackage: () -> String?,
    performBack: suspend () -> Boolean,
    launchSource: suspend () -> Boolean,
    wait: suspend (Long) -> Unit = { delay(it) },
    maxAttempts: Int = 3,
    settleDelayMs: Long = 300L,
    retryDelayMs: Long = 350L,
    sourceStabilityDelayMs: Long = 100L,
): Boolean {
    if (sourcePackage == targetPackage) return false

    suspend fun exactSourceIsStable(): Boolean {
        if (currentPackage() != sourcePackage) return false
        wait(sourceStabilityDelayMs)
        return currentPackage() == sourcePackage
    }

    wait(settleDelayMs)
    repeat(maxAttempts) { attempt ->
        if (exactSourceIsStable()) return true
        if (currentPackage() != targetPackage) {
            if (attempt + 1 < maxAttempts) wait(retryDelayMs)
            return@repeat
        }
        val accepted = performBack()
        if (accepted && exactSourceIsStable()) return true
        if (attempt + 1 < maxAttempts) {
            wait(retryDelayMs)
        }
    }
    if (currentPackage() != targetPackage) return false
    if (!launchSource()) return false
    wait(settleDelayMs)
    return exactSourceIsStable()
}

internal suspend fun publishFirstBlockedNotification(
    outcome: ProtectionOutcome,
    findTarget: suspend (Long) -> FeedbackTarget?,
    countBlocked: suspend (String, String) -> Int,
    publish: (Long, String, String) -> Boolean,
) {
    if (outcome.decision.kind != com.shakeguard.protection.DecisionKind.BLOCK) return
    val eventId = outcome.eventId ?: return

    try {
        val target = findTarget(eventId) ?: return
        if (countBlocked(target.sourcePackage, target.targetPackage) != 1) return
        publish(eventId, target.sourcePackage, target.targetPackage)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Notification failures must not interrupt the protection event loop.
    }
}

internal fun createProtectionCoordinator(
    ruleRepository: RuleRepository,
    allowanceStore: OneTimeAllowanceStore,
    excludedPackages: Set<String>,
    backAction: BackActionExecutor,
    clock: com.shakeguard.protection.MonotonicClock = SystemMonotonicClock,
): ProtectionCoordinator = ProtectionCoordinator(
    sessionTracker = SessionTracker(clock),
    evaluator = RuleEvaluator(excludedPackages),
    ruleStore = ruleRepository,
    allowanceStore = allowanceStore,
    backAction = backAction,
    recorder = RoomProtectionEventRecorder(ruleRepository),
)
