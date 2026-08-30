package com.shakeguard.protection

import com.shakeguard.feedback.OneTimeAllowanceStore

interface ProtectionRuleStore {
    suspend fun findSource(packageName: String): ProtectedSource?
    suspend fun findPairRules(sourcePackage: String, targetPackage: String): List<PairRule>
}

fun interface BackActionExecutor {
    suspend fun goBack(sourcePackage: String, targetPackage: String): Boolean
}

fun interface ProtectionEventRecorder {
    suspend fun record(record: ProtectionRecord): Long
}

enum class ActionResult { NOT_REQUESTED, RETURNED, RETURN_FAILED }

data class ProtectionRecord(
    val transition: ForegroundTransition,
    val decision: Decision,
    val actionResult: ActionResult,
)

data class ProtectionOutcome(
    val decision: Decision,
    val actionResult: ActionResult,
    val eventId: Long?,
)

class ProtectionCoordinator(
    private val sessionTracker: SessionTracker,
    private val evaluator: RuleEvaluator,
    private val ruleStore: ProtectionRuleStore,
    private val allowanceStore: OneTimeAllowanceStore,
    private val backAction: BackActionExecutor,
    private val recorder: ProtectionEventRecorder,
) {
    fun clearProtectionSession() {
        sessionTracker.reset()
    }

    suspend fun onForeground(packageName: String): ProtectionOutcome? {
        val transition = sessionTracker.transitionFromCurrent(packageName)
        if (transition == null) {
            val source = ruleStore.findSource(packageName)
            if (source?.enabled == true) {
                sessionTracker.onForeground(packageName)
            }
            return null
        }

        if (allowanceStore.consume(transition.sourcePackage, transition.targetPackage)) {
            sessionTracker.onForeground(packageName)
            return ProtectionOutcome(
                Decision(DecisionKind.ALLOW, "one-time allowance"),
                ActionResult.NOT_REQUESTED,
                eventId = null,
            )
        }

        val source = ruleStore.findSource(transition.sourcePackage)
        val pairRules = ruleStore.findPairRules(transition.sourcePackage, transition.targetPackage)
        val decision = evaluator.evaluate(transition, source, pairRules)
        val actionResult = when (decision.kind) {
            DecisionKind.BLOCK -> {
                val returned = backAction.goBack(
                    sourcePackage = transition.sourcePackage,
                    targetPackage = transition.targetPackage,
                )
                sessionTracker.markRecovery(transition.sourcePackage)
                if (returned) ActionResult.RETURNED else ActionResult.RETURN_FAILED
            }
            DecisionKind.ALLOW, DecisionKind.OBSERVE -> {
                sessionTracker.onForeground(packageName)
                ActionResult.NOT_REQUESTED
            }
            DecisionKind.IGNORE -> ActionResult.NOT_REQUESTED
        }
        val eventId = if (decision.kind == DecisionKind.BLOCK || decision.kind == DecisionKind.OBSERVE) {
            recorder.record(ProtectionRecord(transition, decision, actionResult))
        } else {
            null
        }
        return ProtectionOutcome(decision, actionResult, eventId)
    }
}
