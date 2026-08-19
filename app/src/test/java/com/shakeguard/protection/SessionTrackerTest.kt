package com.shakeguard.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SessionTrackerTest {
    private val clock = FakeMonotonicClock(1_000L)
    private val tracker = SessionTracker(clock)

    @Test
    fun foregroundSourceStartsProtectedSession() {
        assertEquals(SessionState.Protected("news", 1_000L), tracker.onForeground("news"))
    }

    @Test
    fun repeatedSourceEventDoesNotRestartWindow() {
        tracker.onForeground("news")
        clock.advanceBy(200L)

        assertEquals(SessionState.Protected("news", 1_000L), tracker.onForeground("news"))
    }

    @Test
    fun transitionUsesOriginalSessionStartTime() {
        tracker.onForeground("news")
        clock.advanceBy(450L)

        assertEquals(
            ForegroundTransition("news", "store", 450L),
            tracker.transitionFromCurrent("store"),
        )
    }

    @Test
    fun duplicateTransitionIsSuppressedWithinDedupeWindow() {
        tracker.onForeground("news")
        assertNotNull(tracker.transitionFromCurrent("store"))
        clock.advanceBy(299L)

        assertNull(tracker.transitionFromCurrent("store"))
    }

    @Test
    fun sameTransitionIsEmittedAgainAtDedupeBoundary() {
        tracker.onForeground("news")
        assertNotNull(tracker.transitionFromCurrent("store"))
        clock.advanceBy(300L)

        assertNotNull(tracker.transitionFromCurrent("store"))
    }

    @Test
    fun differentTargetIsNotSuppressed() {
        tracker.onForeground("news")
        assertNotNull(tracker.transitionFromCurrent("store"))

        assertNotNull(tracker.transitionFromCurrent("browser"))
    }

    @Test
    fun observedTargetDoesNotBecomeANewProtectedSource() {
        tracker.onForeground("news")
        tracker.transitionFromCurrent("store")

        assertSame(SessionState.Idle, tracker.onForeground("store"))
        assertNull(tracker.transitionFromCurrent("browser"))
    }

    @Test
    fun duplicateObservedTargetEventDoesNotStartANewSession() {
        tracker.onForeground("news")
        tracker.transitionFromCurrent("store")
        tracker.onForeground("store")
        clock.advanceBy(100L)

        assertSame(SessionState.Idle, tracker.onForeground("store"))
        assertNull(tracker.transitionFromCurrent("browser"))
    }

    @Test
    fun recoveryToExpectedSourceClosesSessionWithoutRestartingWindow() {
        tracker.onForeground("news")
        tracker.markRecovery("news")
        clock.advanceBy(100L)

        assertSame(SessionState.Idle, tracker.onForeground("news"))
        assertNull(tracker.transitionFromCurrent("store"))
    }

    @Test
    fun unexpectedForegroundDuringRecoveryKeepsRecoveryState() {
        tracker.onForeground("news")
        tracker.markRecovery("news")

        assertEquals(SessionState.Recovering("news", 1_000L), tracker.onForeground("system-ui"))
    }

    @Test
    fun clockRollbackNeverProducesNegativeElapsedTime() {
        tracker.onForeground("news")
        clock.setTo(900L)

        assertEquals(0L, tracker.transitionFromCurrent("store")?.elapsedMs)
    }

    @Test
    fun resetClearsSourceAndTransitionDedupeState() {
        tracker.onForeground("news")
        assertNotNull(tracker.transitionFromCurrent("store"))

        tracker.reset()

        assertNull(tracker.transitionFromCurrent("store"))
        assertEquals(SessionState.Protected("news", 1_000L), tracker.onForeground("news"))
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
