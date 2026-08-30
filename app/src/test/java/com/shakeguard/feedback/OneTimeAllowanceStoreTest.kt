package com.shakeguard.feedback

import com.shakeguard.protection.MonotonicClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeAllowanceStoreTest {
    private val clock = FakeMonotonicClock(1_000L)
    private val store = OneTimeAllowanceStore(clock)

    @Test
    fun exactPairCanBeConsumedOnlyOnce() = runBlocking {
        store.grant(source = "news", target = "store")

        assertTrue(store.consume(source = "news", target = "store"))
        assertFalse(store.consume(source = "news", target = "store"))
    }

    @Test
    fun mismatchedSourceOrTargetDoesNotConsumeExactAllowance() = runBlocking {
        store.grant(source = "news", target = "store")

        assertFalse(store.consume(source = "browser", target = "store"))
        assertFalse(store.consume(source = "news", target = "settings"))
        assertTrue(store.consume(source = "news", target = "store"))
    }

    @Test
    fun allowanceIsValidAtThirtySecondBoundary() = runBlocking {
        store.grant(source = "news", target = "store")
        clock.advanceBy(30_000L)

        assertTrue(store.consume(source = "news", target = "store"))
    }

    @Test
    fun allowanceExpiresAfterThirtySeconds() = runBlocking {
        store.grant(source = "news", target = "store")
        clock.advanceBy(30_001L)

        assertFalse(store.consume(source = "news", target = "store"))
    }

    @Test
    fun clockRollbackInvalidatesAllowance() = runBlocking {
        store.grant(source = "news", target = "store")
        clock.setTo(999L)

        assertFalse(store.consume(source = "news", target = "store"))
    }

    @Test
    fun rollbackRemovesAllowanceEvenAfterClockIsRestored() = runBlocking {
        store.grant(source = "news", target = "store")
        clock.setTo(999L)

        assertFalse(store.consume(source = "news", target = "store"))

        clock.setTo(1_000L)
        assertFalse(store.consume(source = "news", target = "store"))
    }

    @Test
    fun revokeRemovesUnconsumedExactAllowance() = runBlocking {
        store.grant(source = "news", target = "store")

        store.revoke(source = "news", target = "store")

        assertFalse(store.consume(source = "news", target = "store"))
    }

    @Test
    fun revokeLeavesOtherAllowancePairConsumable() = runBlocking {
        store.grant(source = "news", target = "store")
        store.grant(source = "news", target = "browser")

        store.revoke(source = "news", target = "store")

        assertFalse(store.consume(source = "news", target = "store"))
        assertTrue(store.consume(source = "news", target = "browser"))
    }

    @Test
    fun concurrentConsumesYieldExactlyOneSuccess() = runBlocking {
        store.grant(source = "news", target = "store")

        val successfulConsumes = coroutineScope {
            List(16) {
                async(Dispatchers.Default) {
                    store.consume(source = "news", target = "store")
                }
            }.awaitAll().count { it }
        }

        assertEquals(1, successfulConsumes)
    }

    private class FakeMonotonicClock(initialTime: Long) : MonotonicClock {
        private var currentTime = initialTime

        override fun elapsedRealtime(): Long = currentTime

        fun advanceBy(durationMs: Long) {
            currentTime += durationMs
        }

        fun setTo(timeMs: Long) {
            currentTime = timeMs
        }
    }
}
