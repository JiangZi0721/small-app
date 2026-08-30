package com.shakeguard.feedback

import com.shakeguard.protection.MonotonicClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OneTimeAllowanceStore(
    private val clock: MonotonicClock,
    private val ttlMs: Long = 30_000L,
) {
    private val mutex = Mutex()
    private val allowances = mutableMapOf<AllowanceKey, Allowance>()

    suspend fun grant(source: String, target: String) {
        mutex.withLock {
            val createdAt = clock.elapsedRealtime()
            allowances[AllowanceKey(source, target)] = Allowance(createdAt, createdAt + ttlMs)
        }
    }

    suspend fun consume(source: String, target: String): Boolean = mutex.withLock {
        val key = AllowanceKey(source, target)
        val allowance = allowances[key] ?: return@withLock false
        val now = clock.elapsedRealtime()

        if (now < allowance.createdAt || now > allowance.expiresAt) {
            allowances.remove(key)
            return@withLock false
        }

        allowances.remove(key)
        true
    }

    suspend fun revoke(source: String, target: String) {
        mutex.withLock {
            allowances.remove(AllowanceKey(source, target))
        }
    }

    private data class AllowanceKey(val source: String, val target: String)

    private data class Allowance(val createdAt: Long, val expiresAt: Long)
}
