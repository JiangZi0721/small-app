package com.shakeguard.data

import com.shakeguard.feedback.FeedbackTarget
import com.shakeguard.protection.ActionResult
import com.shakeguard.protection.DecisionKind
import com.shakeguard.protection.PairRule
import com.shakeguard.protection.ProtectedSource
import com.shakeguard.protection.ProtectionRuleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ManagedSource(
    val packageName: String,
    val enabled: Boolean,
    val windowMs: Long,
    val sourceLevelBlock: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ActivityEvent(
    val id: Long,
    val sourcePackage: String,
    val targetPackage: String,
    val elapsedMs: Long,
    val decision: DecisionKind,
    val matchedRuleId: Long?,
    val actionResult: ActionResult,
    val userFeedback: String?,
    val createdAt: Long,
)

data class ManagedPairRule(
    val id: Long,
    val sourcePackage: String,
    val targetPackage: String,
    val kind: com.shakeguard.protection.RuleKind,
    val enabled: Boolean,
    val origin: String,
    val updatedAt: Long,
)

class RuleRepository internal constructor(
    private val sourceDao: ProtectedSourceDao,
    private val pairRuleDao: PairRuleDao,
    private val jumpEventDao: JumpEventDao,
    private val transactionRunner: RuleTransactionRunner = RuleTransactionRunner { block -> block() },
) : ProtectionRuleStore {
    private val ruleWriteMutex = Mutex()
    fun observeEnabledSources(): Flow<List<ProtectedSource>> =
        sourceDao.observeEnabled().map { entities -> entities.map { it.toDomain() } }

    fun observeAllSources(): Flow<List<ManagedSource>> =
        sourceDao.observeAll().map { entities -> entities.map { it.toManagedSource() } }

    fun observeRecentEvents(limit: Int = 100): Flow<List<ActivityEvent>> =
        jumpEventDao.observeRecent(limit).map { entities -> entities.mapNotNull { it.toActivityEventOrNull() } }

    fun observeAllRules(): Flow<List<ManagedPairRule>> =
        pairRuleDao.observeAll().map { entities -> entities.mapNotNull { it.toManagedPairRuleOrNull() } }

    fun countRulesForSource(packageName: String): Flow<Int> =
        observeAllRules().map { rules -> rules.count { it.sourcePackage == packageName } }

    override suspend fun findSource(packageName: String): ProtectedSource? =
        sourceDao.find(packageName)?.toDomain()

    override suspend fun findPairRules(sourcePackage: String, targetPackage: String): List<PairRule> =
        pairRuleDao.find(sourcePackage, targetPackage).mapNotNull { it.toDomainOrNull() }

    suspend fun saveSource(source: ProtectedSource, updatedAt: Long) {
        val existing = sourceDao.find(source.packageName)
        sourceDao.upsert(
            ProtectedSourceEntity(
                packageName = source.packageName,
                enabled = source.enabled,
                windowMs = source.windowMs,
                sourceLevelBlock = source.sourceLevelBlock,
                updatedAt = updatedAt,
                createdAt = existing?.createdAt ?: updatedAt,
            ),
        )
    }

    suspend fun saveManagedSource(source: ManagedSource, updatedAt: Long) {
        val existing = sourceDao.find(source.packageName)
        sourceDao.upsert(
            ProtectedSourceEntity(
                packageName = source.packageName,
                enabled = source.enabled,
                windowMs = source.windowMs,
                sourceLevelBlock = source.sourceLevelBlock,
                updatedAt = updatedAt,
                createdAt = existing?.createdAt ?: source.createdAt,
            ),
        )
    }

    suspend fun setSourceEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Boolean =
        sourceDao.setEnabled(packageName, enabled, updatedAt) > 0

    suspend fun setRuleEnabled(id: Long, enabled: Boolean, updatedAt: Long): Boolean =
        pairRuleDao.setEnabled(id, enabled, updatedAt) > 0

    suspend fun deleteRule(id: Long): Boolean = pairRuleDao.delete(id) > 0

    // Serializes same-process rule writes; the production runner adds the Room transaction boundary.
    suspend fun saveManagedRule(rule: ManagedPairRule, updatedAt: Long): Long = ruleWriteMutex.withLock {
        transactionRunner.run {
            val existing = pairRuleDao.findByKind(rule.sourcePackage, rule.targetPackage, rule.kind.name)
            if (existing == null) {
                pairRuleDao.insert(
                    PairRuleEntity(
                        sourcePackage = rule.sourcePackage,
                        targetPackage = rule.targetPackage,
                        kind = rule.kind.name,
                        enabled = rule.enabled,
                        origin = rule.origin,
                        updatedAt = updatedAt,
                    ),
                )
            } else {
                val updated = existing.copy(
                    enabled = rule.enabled,
                    origin = rule.origin,
                    updatedAt = updatedAt,
                )
                check(pairRuleDao.update(updated) == 1) { "Pair rule ${existing.id} could not be updated" }
                existing.id
            }
        }
    }

    suspend fun addPairRule(rule: PairRule, origin: String, updatedAt: Long): Long =
        pairRuleDao.insert(
            PairRuleEntity(
                id = rule.id,
                sourcePackage = rule.sourcePackage,
                targetPackage = rule.targetPackage,
                kind = rule.kind.name,
                enabled = rule.enabled,
                origin = origin,
                updatedAt = updatedAt,
            ),
        )

    suspend fun recordEvent(event: JumpEventEntity): Long = jumpEventDao.insert(event)

    suspend fun findFeedbackTarget(eventId: Long): FeedbackTarget? =
        jumpEventDao.find(eventId)?.let { event ->
            FeedbackTarget(event.id, event.sourcePackage, event.targetPackage)
        }

    suspend fun countBlockedEvents(source: String, target: String): Int =
        jumpEventDao.countBlocks(source, target)

    suspend fun deleteEventsBefore(before: Long) {
        jumpEventDao.deleteBefore(before)
    }

    suspend fun clearAllEvents(): Int = jumpEventDao.deleteAll()
}

private fun ProtectedSourceEntity.toDomain() = ProtectedSource(
    packageName = packageName,
    enabled = enabled,
    windowMs = windowMs,
    sourceLevelBlock = sourceLevelBlock,
)

private fun ProtectedSourceEntity.toManagedSource() = ManagedSource(
    packageName = packageName,
    enabled = enabled,
    windowMs = windowMs,
    sourceLevelBlock = sourceLevelBlock,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun JumpEventEntity.toActivityEventOrNull(): ActivityEvent? {
    val parsedDecision = runCatching { DecisionKind.valueOf(decision) }.getOrNull() ?: return null
    val parsedActionResult = runCatching { ActionResult.valueOf(actionResult) }.getOrNull() ?: return null
    return ActivityEvent(
        id = id,
        sourcePackage = sourcePackage,
        targetPackage = targetPackage,
        elapsedMs = elapsedMs,
        decision = parsedDecision,
        matchedRuleId = matchedRuleId,
        actionResult = parsedActionResult,
        userFeedback = userFeedback,
        createdAt = createdAt,
    )
}

private fun PairRuleEntity.toDomainOrNull(): PairRule? {
    val parsedKind = runCatching { com.shakeguard.protection.RuleKind.valueOf(kind) }.getOrNull()
        ?: return null
    return PairRule(
        id = id,
        sourcePackage = sourcePackage,
        targetPackage = targetPackage,
        kind = parsedKind,
        enabled = enabled,
    )
}

private fun PairRuleEntity.toManagedPairRuleOrNull(): ManagedPairRule? {
    val parsedKind = runCatching { com.shakeguard.protection.RuleKind.valueOf(kind) }.getOrNull()
        ?: return null
    return ManagedPairRule(id, sourcePackage, targetPackage, parsedKind, enabled, origin, updatedAt)
}
