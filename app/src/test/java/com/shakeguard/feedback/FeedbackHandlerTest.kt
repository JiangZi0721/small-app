package com.shakeguard.feedback

import com.shakeguard.protection.EpochClock
import com.shakeguard.protection.MonotonicClock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackHandlerTest {
    @Test
    fun allowOnceGrantsExactTokenAndPersistsFeedback() = runBlocking {
        val repository = FakeFeedbackRepository()
        val allowanceStore = OneTimeAllowanceStore(FakeMonotonicClock())
        val handler = FeedbackHandler(repository, allowanceStore, FakeEpochClock(100L))
        val command = FeedbackCommand.AllowOnce(eventId = 7L)

        val result = handler.handle(command)

        assertEquals(FeedbackResult.Applied(command), result)
        assertEquals(FeedbackTarget(7L, "news", "store"), repository.markedAllowOnce)
        assertFalse(allowanceStore.consume("other", "store"))
        assertFalse(allowanceStore.consume("news", "other"))
        assertTrue(allowanceStore.consume("news", "store"))
    }

    @Test
    fun allowPairDelegatesTargetAndTimestamp() = runBlocking {
        val repository = FakeFeedbackRepository()
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(200L))
        val command = FeedbackCommand.AllowPair(eventId = 7L)

        val result = handler.handle(command)

        assertEquals(FeedbackResult.Applied(command), result)
        assertEquals(FeedbackTarget(7L, "news", "store") to 200L, repository.allowPairCall)
    }

    @Test
    fun repeatedAllowPairCommandsDelegateTheSameExactTarget() = runBlocking {
        val repository = FakeFeedbackRepository()
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(250L))
        val command = FeedbackCommand.AllowPair(eventId = 7L)

        handler.handle(command)
        handler.handle(command)

        assertEquals(
            listOf(
                FeedbackTarget(7L, "news", "store"),
                FeedbackTarget(7L, "news", "store"),
            ),
            repository.allowPairCalls,
        )
    }

    @Test
    fun confirmAdDelegatesTargetAndTimestamp() = runBlocking {
        val repository = FakeFeedbackRepository()
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(300L))
        val command = FeedbackCommand.ConfirmAd(eventId = 7L)

        val result = handler.handle(command)

        assertEquals(FeedbackResult.Applied(command), result)
        assertEquals(FeedbackTarget(7L, "news", "store") to 300L, repository.confirmAdCall)
    }

    @Test
    fun stopProtectingDelegatesTargetAndTimestamp() = runBlocking {
        val repository = FakeFeedbackRepository()
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(400L))
        val command = FeedbackCommand.StopProtectingSource(eventId = 7L)

        val result = handler.handle(command)

        assertEquals(FeedbackResult.Applied(command), result)
        assertEquals(FeedbackTarget(7L, "news", "store") to 400L, repository.stopProtectingCall)
    }

    @Test
    fun missingEventReturnsNotFoundWithoutMutation() = runBlocking {
        val repository = FakeFeedbackRepository().apply { feedbackTarget = null }
        val allowanceStore = allowanceStore()
        val handler = FeedbackHandler(repository, allowanceStore, FakeEpochClock(500L))

        val result = handler.handle(FeedbackCommand.AllowOnce(eventId = 7L))

        assertEquals(FeedbackResult.NotFound(7L), result)
        assertEquals(1, repository.findCalls)
        assertNull(repository.markedAllowOnce)
        assertFalse(allowanceStore.consume("news", "store"))
    }

    @Test
    fun allowOncePersistenceFailureRevokesExactTokenAndPreservesCause() = runBlocking {
        val cause = IllegalStateException("database unavailable")
        val repository = FakeFeedbackRepository().apply { markAllowOnceError = cause }
        val allowanceStore = allowanceStore()
        val handler = FeedbackHandler(repository, allowanceStore, FakeEpochClock(600L))

        val result = handler.handle(FeedbackCommand.AllowOnce(eventId = 7L))

        assertEquals(FeedbackResult.Failed(7L, cause), result)
        assertFalse(allowanceStore.consume("news", "store"))
        assertFalse(allowanceStore.consume("other", "store"))
        assertFalse(allowanceStore.consume("news", "other"))
    }

    @Test
    fun repositoryFailureReturnsFailedWithOriginalCause() = runBlocking {
        val cause = IllegalStateException("database unavailable")
        val repository = FakeFeedbackRepository().apply { allowPairError = cause }
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(700L))

        val result = handler.handle(FeedbackCommand.AllowPair(eventId = 7L))

        assertTrue(result is FeedbackResult.Failed)
        assertSame(cause, (result as FeedbackResult.Failed).cause)
    }

    @Test
    fun stopProtectingFalseReturnsExplicitSourceFailure() = runBlocking {
        val repository = FakeFeedbackRepository().apply { stopProtectingResult = false }
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(800L))

        val result = handler.handle(FeedbackCommand.StopProtectingSource(eventId = 7L))

        assertTrue(result is FeedbackResult.Failed)
        val cause = (result as FeedbackResult.Failed).cause
        assertTrue(cause is FeedbackSourceNotFoundException)
        assertEquals("Protected source news was not found", cause.message)
    }

    @Test
    fun mismatchedRepositoryTargetIsRejectedBeforeMutation() = runBlocking {
        val repository = FakeFeedbackRepository().apply {
            feedbackTarget = FeedbackTarget(8L, "news", "store")
            returnMismatchedTarget = true
        }
        val allowanceStore = allowanceStore()
        val handler = FeedbackHandler(repository, allowanceStore, FakeEpochClock(900L))

        val result = handler.handle(FeedbackCommand.AllowOnce(eventId = 7L))

        assertTrue(result is FeedbackResult.Failed)
        assertTrue((result as FeedbackResult.Failed).cause is FeedbackTargetMismatchException)
        assertNull(repository.markedAllowOnce)
        assertFalse(allowanceStore.consume("news", "store"))
    }

    @Test
    fun cancellationIsPropagatedAndAllowOnceTokenIsRevoked() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val repository = FakeFeedbackRepository().apply { markAllowOnceError = cancellation }
        val allowanceStore = allowanceStore()
        val handler = FeedbackHandler(repository, allowanceStore, FakeEpochClock(1_000L))

        val thrown = org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking { handler.handle(FeedbackCommand.AllowOnce(eventId = 7L)) }
        }

        assertSame(cancellation, thrown)
        assertFalse(allowanceStore.consume("news", "store"))
    }

    @Test
    fun concurrentHandlesAreSerializedAcrossLookupAndMutation() = runBlocking {
        val repository = FakeFeedbackRepository().apply { serialDelayMs = 20L }
        val handler = FeedbackHandler(repository, allowanceStore(), FakeEpochClock(1_100L))

        coroutineScope {
            listOf(
                FeedbackCommand.AllowPair(eventId = 7L),
                FeedbackCommand.ConfirmAd(eventId = 7L),
                FeedbackCommand.AllowPair(eventId = 7L),
            ).map { command ->
                async(Dispatchers.Default) { handler.handle(command) }
            }.awaitAll()
        }

        assertEquals(1, repository.maxConcurrentCalls)
    }

    private fun allowanceStore() = OneTimeAllowanceStore(FakeMonotonicClock())

    private class FakeFeedbackRepository : FeedbackRepository {
        var feedbackTarget: FeedbackTarget? = FeedbackTarget(7L, "news", "store")
        var returnMismatchedTarget = false
        var findCalls = 0
        var markedAllowOnce: FeedbackTarget? = null
        var allowPairCall: Pair<FeedbackTarget, Long>? = null
        val allowPairCalls = mutableListOf<FeedbackTarget>()
        var confirmAdCall: Pair<FeedbackTarget, Long>? = null
        var stopProtectingCall: Pair<FeedbackTarget, Long>? = null
        var markAllowOnceError: Exception? = null
        var allowPairError: Exception? = null
        var stopProtectingResult = true
        var serialDelayMs = 0L
        private val activeCalls = AtomicInteger(0)
        private val maxActiveCalls = AtomicInteger(0)
        val maxConcurrentCalls: Int
            get() = maxActiveCalls.get()

        override suspend fun findFeedbackTarget(eventId: Long): FeedbackTarget? = track {
            findCalls += 1
            feedbackTarget?.takeIf { returnMismatchedTarget || it.eventId == eventId }
        }

        override suspend fun markAllowOnce(target: FeedbackTarget) = track {
            markAllowOnceError?.let { throw it }
            markedAllowOnce = target
        }

        override suspend fun applyAllowPair(target: FeedbackTarget, updatedAt: Long): Long = track {
            allowPairError?.let { throw it }
            allowPairCall = target to updatedAt
            allowPairCalls += target
            1L
        }

        override suspend fun applyConfirmAd(target: FeedbackTarget, updatedAt: Long): Long = track {
            confirmAdCall = target to updatedAt
            2L
        }

        override suspend fun applyStopProtecting(target: FeedbackTarget, updatedAt: Long): Boolean = track {
            stopProtectingCall = target to updatedAt
            stopProtectingResult
        }

        override suspend fun countBlockedEvents(source: String, target: String): Int = 0

        private suspend fun <T> track(block: suspend () -> T): T {
            val active = activeCalls.incrementAndGet()
            updateMaximum(active)
            return try {
                if (serialDelayMs > 0L) delay(serialDelayMs)
                block()
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        private fun updateMaximum(active: Int) {
            while (true) {
                val currentMaximum = maxActiveCalls.get()
                if (active <= currentMaximum || maxActiveCalls.compareAndSet(currentMaximum, active)) return
            }
        }
    }

    private class FakeEpochClock(private val now: Long) : EpochClock {
        override fun currentTimeMillis(): Long = now
    }

    private class FakeMonotonicClock : MonotonicClock {
        override fun elapsedRealtime(): Long = 1_000L
    }
}
