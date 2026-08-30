package com.shakeguard.protection

import com.shakeguard.feedback.OneTimeAllowanceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectionCoordinatorTest {
    @Test
    fun clearingProtectionSessionPreventsTargetFromReusingPriorSource() = runBlocking {
        val clock = FakeClock(1_000L)
        val back = FakeBackAction(succeeds = true)
        val recorder = FakeRecorder()
        val coordinator = coordinator(clock, back, recorder)

        coordinator.onForeground("news")
        coordinator.clearProtectionSession()

        assertNull(coordinator.onForeground("store"))
        assertEquals(0, back.callCount)
        assertEquals(0, recorder.records.size)
    }

    @Test
    fun blockedOutcomeIncludesPersistentEventId() = runBlocking {
        val clock = FakeClock(1_000L)
        val recorder = FakeRecorder(eventId = 42L)
        val coordinator = coordinator(clock, FakeBackAction(succeeds = true), recorder)

        coordinator.onForeground("news")
        clock.advanceBy(100L)
        val outcome = coordinator.onForeground("store")

        assertEquals(42L, outcome?.eventId)
    }

    @Test
    fun oneTimeAllowanceOverridesSourceBlockAndIsConsumed() = runBlocking {
        val clock = FakeClock(1_000L)
        val allowanceStore = OneTimeAllowanceStore(clock)
        val back = FakeBackAction(succeeds = true)
        val recorder = FakeRecorder()
        val coordinator = coordinator(clock, back, recorder, allowanceStore)

        allowanceStore.grant("news", "store")
        assertNull(coordinator.onForeground("news"))
        val allowed = coordinator.onForeground("store")

        assertEquals(DecisionKind.ALLOW, allowed?.decision?.kind)
        assertEquals("one-time allowance", allowed?.decision?.reason)
        assertEquals(ActionResult.NOT_REQUESTED, allowed?.actionResult)
        assertNull(allowed?.eventId)
        assertEquals(0, back.callCount)
        assertEquals(0, recorder.records.size)

        clock.advanceBy(300L)
        assertNull(coordinator.onForeground("news"))
        val blockedAgain = coordinator.onForeground("store")

        assertEquals(DecisionKind.BLOCK, blockedAgain?.decision?.kind)
        assertEquals(1L, blockedAgain?.eventId)
        assertEquals(1, back.callCount)
        assertEquals("news", back.lastSourcePackage)
        assertEquals("store", back.lastTargetPackage)
        assertEquals(1, recorder.records.size)
    }

    @Test
    fun observedOutcomeIncludesPersistentEventIdWithoutGoingBack() = runBlocking {
        val clock = FakeClock(1_000L)
        val back = FakeBackAction(succeeds = true)
        val recorder = FakeRecorder(eventId = 77L)
        val coordinator = coordinator(clock, back, recorder, sourceLevelBlock = false)

        coordinator.onForeground("news")
        clock.advanceBy(100L)
        val outcome = coordinator.onForeground("browser")

        assertEquals(DecisionKind.OBSERVE, outcome?.decision?.kind)
        assertEquals(77L, outcome?.eventId)
        assertEquals(ActionResult.NOT_REQUESTED, outcome?.actionResult)
        assertEquals(0, back.callCount)
        assertEquals(1, recorder.records.size)
    }

    @Test
    fun blockedTransitionExecutesOneBackAndRecordsOutcome() = runBlocking {
        val clock = FakeClock(1_000L)
        val store = FakeRuleStore(
            sources = mapOf("news" to ProtectedSource("news", windowMs = 5_000L)),
        )
        val back = FakeBackAction(succeeds = true)
        val recorder = FakeRecorder()
        val coordinator = ProtectionCoordinator(
            sessionTracker = SessionTracker(clock),
            evaluator = RuleEvaluator(),
            ruleStore = store,
            allowanceStore = OneTimeAllowanceStore(clock),
            backAction = back,
            recorder = recorder,
        )

        assertNull(coordinator.onForeground("news"))
        clock.advanceBy(100L)
        val outcome = coordinator.onForeground("store")
        coordinator.onForeground("store")

        assertEquals(DecisionKind.BLOCK, outcome?.decision?.kind)
        assertEquals(1L, outcome?.eventId)
        assertEquals(ActionResult.RETURNED, outcome?.actionResult)
        assertEquals(1, back.callCount)
        assertEquals(1, recorder.records.size)
        assertEquals("news", recorder.records.single().transition.sourcePackage)
        assertEquals("store", recorder.records.single().transition.targetPackage)
    }

    @Test
    fun failedBackIsRecordedAndIsNotRetriedForDuplicateTarget() = runBlocking {
        val clock = FakeClock(1_000L)
        val back = FakeBackAction(succeeds = false)
        val recorder = FakeRecorder()
        val coordinator = coordinator(clock, back, recorder)

        coordinator.onForeground("news")
        val outcome = coordinator.onForeground("store")
        coordinator.onForeground("store")

        assertEquals(ActionResult.RETURN_FAILED, outcome?.actionResult)
        assertEquals(1, back.callCount)
        assertEquals(ActionResult.RETURN_FAILED, recorder.records.single().actionResult)
    }

    @Test
    fun unconfiguredAppNeverStartsProtectionSession() = runBlocking {
        val clock = FakeClock(1_000L)
        val back = FakeBackAction(succeeds = true)
        val recorder = FakeRecorder()
        val coordinator = ProtectionCoordinator(
            SessionTracker(clock),
            RuleEvaluator(),
            FakeRuleStore(),
            OneTimeAllowanceStore(clock),
            back,
            recorder,
        )

        coordinator.onForeground("unconfigured")
        val outcome = coordinator.onForeground("store")

        assertNull(outcome)
        assertEquals(0, back.callCount)
        assertEquals(0, recorder.records.size)
    }

    @Test
    fun outsideWindowDoesNotReturnOrCreateEventRecord() = runBlocking {
        val clock = FakeClock(1_000L)
        val back = FakeBackAction(succeeds = true)
        val recorder = FakeRecorder()
        val coordinator = coordinator(clock, back, recorder)

        coordinator.onForeground("news")
        clock.advanceBy(5_001L)
        val outcome = coordinator.onForeground("store")

        assertEquals(DecisionKind.ALLOW, outcome?.decision?.kind)
        assertNull(outcome?.eventId)
        assertEquals(0, back.callCount)
        assertEquals(0, recorder.records.size)
    }

    @Test
    fun ignoredOutcomeDoesNotCreateEventId() = runBlocking {
        val clock = FakeClock(1_000L)
        val recorder = FakeRecorder(eventId = 42L)
        val coordinator = coordinator(clock, FakeBackAction(succeeds = true), recorder)

        coordinator.onForeground("news")
        val outcome = coordinator.onForeground("news")

        assertEquals(DecisionKind.IGNORE, outcome?.decision?.kind)
        assertNull(outcome?.eventId)
        assertEquals(0, recorder.records.size)
    }

    private fun coordinator(
        clock: FakeClock,
        back: FakeBackAction,
        recorder: FakeRecorder,
        allowanceStore: OneTimeAllowanceStore = OneTimeAllowanceStore(clock),
        sourceLevelBlock: Boolean = true,
    ) = ProtectionCoordinator(
        SessionTracker(clock),
        RuleEvaluator(),
        FakeRuleStore(
            mapOf("news" to ProtectedSource("news", windowMs = 5_000L, sourceLevelBlock = sourceLevelBlock)),
        ),
        allowanceStore,
        back,
        recorder,
    )

    private class FakeClock(initial: Long) : MonotonicClock {
        private var now = initial
        override fun elapsedRealtime(): Long = now
        fun advanceBy(durationMs: Long) {
            now += durationMs
        }
    }

    private class FakeRuleStore(
        private val sources: Map<String, ProtectedSource> = emptyMap(),
        private val pairRules: List<PairRule> = emptyList(),
    ) : ProtectionRuleStore {
        override suspend fun findSource(packageName: String): ProtectedSource? = sources[packageName]
        override suspend fun findPairRules(sourcePackage: String, targetPackage: String): List<PairRule> =
            pairRules.filter { it.sourcePackage == sourcePackage && it.targetPackage == targetPackage }
    }

    private class FakeBackAction(private val succeeds: Boolean) : BackActionExecutor {
        var callCount = 0
        var lastSourcePackage: String? = null
        var lastTargetPackage: String? = null

        override suspend fun goBack(sourcePackage: String, targetPackage: String): Boolean {
            callCount += 1
            lastSourcePackage = sourcePackage
            lastTargetPackage = targetPackage
            return succeeds
        }
    }

    private class FakeRecorder(private val eventId: Long = 1L) : ProtectionEventRecorder {
        val records = mutableListOf<ProtectionRecord>()
        override suspend fun record(record: ProtectionRecord): Long {
            records += record
            return eventId
        }
    }
}
