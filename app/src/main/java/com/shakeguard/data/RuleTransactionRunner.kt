package com.shakeguard.data

internal fun interface RuleTransactionRunner {
    suspend fun run(block: suspend () -> Long): Long
}
