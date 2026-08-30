package com.shakeguard.protection

sealed interface SessionState {
    data object Idle : SessionState
    data class Protected(val sourcePackage: String, val startedAt: Long) : SessionState
    data class Recovering(val sourcePackage: String, val expectedAt: Long) : SessionState
}

class SessionTracker(
    private val clock: MonotonicClock,
    private val dedupeWindowMs: Long = 300L,
) {
    private var state: SessionState = SessionState.Idle
    private var lastTransition: Pair<String, String>? = null
    private var lastTransitionAt: Long? = null

    fun onForeground(packageName: String): SessionState {
        val now = clock.elapsedRealtime()
        if (state is SessionState.Recovering) {
            val recovering = state as SessionState.Recovering
            if (packageName == recovering.sourcePackage) {
                state = SessionState.Idle
                return state
            }
        }
        val protected = state as? SessionState.Protected
        val previous = protected?.sourcePackage
        if (previous != null && previous != packageName) {
            state = SessionState.Idle
            if (lastTransition == Pair(previous, packageName)) {
                return state
            }
        }
        val previousTransitionAt = lastTransitionAt
        if (
            state is SessionState.Idle &&
            lastTransition?.second == packageName &&
            previousTransitionAt != null &&
            now >= previousTransitionAt &&
            now - previousTransitionAt < dedupeWindowMs
        ) {
            return state
        }
        if (state is SessionState.Idle) {
            state = SessionState.Protected(packageName, now)
        }
        return state
    }

    fun transitionFromCurrent(targetPackage: String): ForegroundTransition? {
        val protected = state as? SessionState.Protected ?: return null
        val now = clock.elapsedRealtime()
        val transitionKey = Pair(protected.sourcePackage, targetPackage)
        val previousTransitionAt = lastTransitionAt
        if (
            lastTransition == transitionKey &&
            previousTransitionAt != null &&
            now >= previousTransitionAt &&
            now - previousTransitionAt < dedupeWindowMs
        ) {
            return null
        }
        lastTransition = transitionKey
        lastTransitionAt = now
        val elapsed = (now - protected.startedAt).coerceAtLeast(0L)
        return ForegroundTransition(protected.sourcePackage, targetPackage, elapsed, protected.sourcePackage == targetPackage)
    }

    fun markRecovery(expectedSource: String) {
        state = SessionState.Recovering(expectedSource, clock.elapsedRealtime())
    }

    fun reset() {
        state = SessionState.Idle
        lastTransition = null
        lastTransitionAt = null
    }
}
