package com.shakeguard.accessibility

import com.shakeguard.feedback.FeedbackTarget
import com.shakeguard.protection.ActionResult
import com.shakeguard.protection.Decision
import com.shakeguard.protection.DecisionKind
import com.shakeguard.protection.ProtectionOutcome
import com.shakeguard.protection.ProtectionGateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstBlockNotificationTest {
    @Test
    fun disabledAndNotReadyClearSessionWithoutEvaluatingOrPublishing() = runBlocking {
        listOf(ProtectionGateState.Disabled, ProtectionGateState.NotReady).forEach { gateState ->
            var clearCalls = 0
            var evaluateCalls = 0
            var publishCalls = 0

            handleForegroundForProtection(
                packageName = "store",
                gateState = gateState,
                clearSession = { clearCalls += 1 },
                evaluate = { evaluateCalls += 1; blockedOutcome(42L) },
                publishFirstBlock = { publishCalls += 1 },
            )

            assertEquals(1, clearCalls)
            assertEquals(0, evaluateCalls)
            assertEquals(0, publishCalls)
        }
    }

    @Test
    fun enabledEvaluatesAndPublishesOutcome() = runBlocking {
        var clearCalls = 0
        var evaluatedPackage: String? = null
        var publishedOutcome: ProtectionOutcome? = null
        val outcome = blockedOutcome(42L)

        handleForegroundForProtection(
            packageName = "store",
            gateState = ProtectionGateState.Enabled,
            clearSession = { clearCalls += 1 },
            evaluate = { packageName -> evaluatedPackage = packageName; outcome },
            publishFirstBlock = { publishedOutcome = it },
        )

        assertEquals(0, clearCalls)
        assertEquals("store", evaluatedPackage)
        assertSame(outcome, publishedOutcome)
    }

    @Test
    fun firstBlockedExactPairPublishesEventAndLabels() = runBlocking {
        val outcome = ProtectionOutcome(
            decision = Decision(DecisionKind.BLOCK, "source rule"),
            actionResult = ActionResult.RETURNED,
            eventId = 42L,
        )
        val lookedUpEventIds = mutableListOf<Long>()
        val countedPairs = mutableListOf<Pair<String, String>>()
        val published = mutableListOf<Triple<Long, String, String>>()

        publishFirstBlockedNotification(
            outcome = outcome,
            findTarget = { eventId ->
                lookedUpEventIds += eventId
                FeedbackTarget(eventId, "source.app", "target.app")
            },
            countBlocked = { source, target ->
                countedPairs += source to target
                1
            },
            publish = { eventId, source, target ->
                published += Triple(eventId, source, target)
                true
            },
        )

        assertEquals(listOf(42L), lookedUpEventIds)
        assertEquals(listOf("source.app" to "target.app"), countedPairs)
        assertEquals(
            listOf(Triple(42L, "source.app", "target.app")),
            published,
        )
    }

    @Test
    fun repeatedExactPairDoesNotPublishAgain() = runBlocking {
        val published = mutableListOf<Long>()

        publishFirstBlockedNotification(
            outcome = blockedOutcome(42L),
            findTarget = { eventId: Long -> FeedbackTarget(eventId, "source.app", "target.app") },
            countBlocked = { source, target ->
                assertEquals("source.app", source)
                assertEquals("target.app", target)
                2
            },
            publish = { eventId, _, _ ->
                published += eventId
                true
            },
        )

        assertTrue(published.isEmpty())
    }

    @Test
    fun nonBlockOrMissingEventIdDoesNotQueryOrPublish() = runBlocking {
        val outcomes = listOf(
            ProtectionOutcome(
                decision = Decision(DecisionKind.OBSERVE, "observed"),
                actionResult = ActionResult.NOT_REQUESTED,
                eventId = 42L,
            ),
            ProtectionOutcome(
                decision = Decision(DecisionKind.BLOCK, "blocked"),
                actionResult = ActionResult.RETURNED,
                eventId = null,
            ),
            ProtectionOutcome(
                decision = Decision(DecisionKind.ALLOW, "allowed"),
                actionResult = ActionResult.NOT_REQUESTED,
                eventId = null,
            ),
            ProtectionOutcome(
                decision = Decision(DecisionKind.IGNORE, "ignored"),
                actionResult = ActionResult.NOT_REQUESTED,
                eventId = null,
            ),
        )
        var lookupCalls = 0
        var countCalls = 0
        var publishCalls = 0

        outcomes.forEach { outcome ->
            publishFirstBlockedNotification(
                outcome = outcome,
                findTarget = { eventId: Long ->
                    lookupCalls += 1
                    FeedbackTarget(eventId = eventId, sourcePackage = "source.app", targetPackage = "target.app")
                },
                countBlocked = { _, _ ->
                    countCalls += 1
                    1
                },
                publish = { _, _, _ ->
                    publishCalls += 1
                    true
                },
            )
        }

        assertEquals(0, lookupCalls)
        assertEquals(0, countCalls)
        assertEquals(0, publishCalls)
    }

    @Test
    fun missingTargetStopsBeforeCountingOrPublishing() = runBlocking {
        var countCalls = 0
        var publishCalls = 0

        publishFirstBlockedNotification(
            outcome = blockedOutcome(42L),
            findTarget = { null },
            countBlocked = { _, _ ->
                countCalls += 1
                1
            },
            publish = { _, _, _ ->
                publishCalls += 1
                true
            },
        )

        assertEquals(0, countCalls)
        assertEquals(0, publishCalls)
    }

    @Test
    fun lookupAndCountFailuresAreIsolatedFromProtectionOutcome() = runBlocking {
        val outcome = blockedOutcome(42L)
        var publishCalls = 0

        publishFirstBlockedNotification(
            outcome = outcome,
            findTarget = { throw IllegalStateException("event query failed") },
            countBlocked = { _, _ -> 1 },
            publish = { _, _, _ ->
                publishCalls += 1
                true
            },
        )
        publishFirstBlockedNotification(
            outcome = outcome,
            findTarget = { eventId: Long -> FeedbackTarget(eventId, "source.app", "target.app") },
            countBlocked = { _, _ -> throw IllegalStateException("count query failed") },
            publish = { _, _, _ ->
                publishCalls += 1
                true
            },
        )

        assertEquals(DecisionKind.BLOCK, outcome.decision.kind)
        assertEquals(42L, outcome.eventId)
        assertEquals(0, publishCalls)
    }

    @Test
    fun publishFailureIsIsolatedAndDoesNotChangeProtectionOutcome() = runBlocking {
        val outcome = blockedOutcome(42L)

        publishFirstBlockedNotification(
            outcome = outcome,
            findTarget = { eventId: Long -> FeedbackTarget(eventId, "source.app", "target.app") },
            countBlocked = { _, _ -> 1 },
            publish = { _, _, _ -> throw IllegalStateException("notification denied") },
        )

        assertEquals(DecisionKind.BLOCK, outcome.decision.kind)
        assertEquals(ActionResult.RETURNED, outcome.actionResult)
        assertEquals(42L, outcome.eventId)
    }

    @Test
    fun publishFalseIsIsolatedAndDoesNotChangeProtectionOutcome() = runBlocking {
        val outcome = blockedOutcome(42L)

        publishFirstBlockedNotification(
            outcome = outcome,
            findTarget = { eventId: Long -> FeedbackTarget(eventId, "source.app", "target.app") },
            countBlocked = { _, _ -> 1 },
            publish = { _, _, _ -> false },
        )

        assertEquals(DecisionKind.BLOCK, outcome.decision.kind)
        assertEquals(ActionResult.RETURNED, outcome.actionResult)
        assertEquals(42L, outcome.eventId)
    }

    @Test
    fun cancellationIsRethrownInsteadOfBeingSwallowed() = runBlocking {
        val cancellation = CancellationException("service cancelled")

        val thrown = org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking {
                publishFirstBlockedNotification(
                    outcome = blockedOutcome(42L),
                    findTarget = { throw cancellation },
                    countBlocked = { _, _ -> 1 },
                    publish = { _, _, _ -> true },
                )
            }
        }

        assertSame(cancellation, thrown)
    }

    private fun blockedOutcome(eventId: Long) = ProtectionOutcome(
        decision = Decision(DecisionKind.BLOCK, "source rule"),
        actionResult = ActionResult.RETURNED,
        eventId = eventId,
    )
}
