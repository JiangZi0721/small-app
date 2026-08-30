package com.shakeguard.protection

enum class RuleKind { BLOCK, ALLOW }

enum class DecisionKind { IGNORE, ALLOW, BLOCK, OBSERVE }

data class ProtectedSource(
    val packageName: String,
    val enabled: Boolean = true,
    val windowMs: Long = 5_000L,
    val sourceLevelBlock: Boolean = true,
)

data class PairRule(
    val id: Long,
    val sourcePackage: String,
    val targetPackage: String,
    val kind: RuleKind,
    val enabled: Boolean = true,
)

data class ForegroundTransition(
    val sourcePackage: String,
    val targetPackage: String,
    val elapsedMs: Long,
    val isSamePackage: Boolean = false,
)

data class Decision(
    val kind: DecisionKind,
    val reason: String,
    val ruleId: Long? = null,
)
