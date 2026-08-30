package com.shakeguard.protection

fun interface MonotonicClock {
    fun elapsedRealtime(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

fun interface EpochClock {
    fun currentTimeMillis(): Long
}

object SystemEpochClock : EpochClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
