package com.shakeguard.data

import androidx.room.withTransaction
import com.shakeguard.feedback.FeedbackEventNotFoundException
import com.shakeguard.feedback.FeedbackRepository
import com.shakeguard.feedback.FeedbackTarget
import com.shakeguard.feedback.FeedbackTargetMismatchException

internal fun interface FeedbackTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

class RoomFeedbackRepository internal constructor(
    private val sourceDao: ProtectedSourceDao,
    private val pairRuleDao: PairRuleDao,
    private val jumpEventDao: JumpEventDao,
    private val transactionRunner: FeedbackTransactionRunner,
) : FeedbackRepository {
    constructor(database: AppDatabase) : this(
        sourceDao = database.protectedSourceDao(),
        pairRuleDao = database.pairRuleDao(),
        jumpEventDao = database.jumpEventDao(),
        transactionRunner = FeedbackTransactionRunner { block ->
            database.withTransaction { block() }
        },
    )

    override suspend fun findFeedbackTarget(eventId: Long): FeedbackTarget? =
        jumpEventDao.find(eventId)?.let { event ->
            FeedbackTarget(event.id, event.sourcePackage, event.targetPackage)
        }

    override suspend fun markAllowOnce(target: FeedbackTarget) {
        requireEvent(target)
        if (jumpEventDao.updateFeedback(target.eventId, "ALLOW_ONCE") != 1) {
            throw FeedbackEventNotFoundException(target.eventId)
        }
    }

    override suspend fun applyAllowPair(target: FeedbackTarget, updatedAt: Long): Long {
        return applyPairFeedback(
            target = target,
            updatedAt = updatedAt,
            feedback = "ALLOW_PAIR",
            kind = "ALLOW",
        )
    }

    override suspend fun applyConfirmAd(target: FeedbackTarget, updatedAt: Long): Long {
        return applyPairFeedback(
            target = target,
            updatedAt = updatedAt,
            feedback = "AD",
            kind = "BLOCK",
        )
    }

    private suspend fun applyPairFeedback(
        target: FeedbackTarget,
        updatedAt: Long,
        feedback: String,
        kind: String,
    ): Long {
        var ruleId = 0L
        transactionRunner.run {
            requireEvent(target)
            if (jumpEventDao.updateFeedback(target.eventId, feedback) != 1) {
                throw FeedbackEventNotFoundException(target.eventId)
            }

            val rule = pairRuleDao.findByKind(target.sourcePackage, target.targetPackage, kind)
            ruleId = if (rule == null) {
                pairRuleDao.insert(
                    PairRuleEntity(
                        sourcePackage = target.sourcePackage,
                        targetPackage = target.targetPackage,
                        kind = kind,
                        enabled = true,
                        origin = "FEEDBACK",
                        updatedAt = updatedAt,
                    ),
                )
            } else {
                val updated = rule.copy(enabled = true, origin = "FEEDBACK", updatedAt = updatedAt)
                if (pairRuleDao.update(updated) != 1) {
                    throw IllegalStateException("Feedback $kind rule ${rule.id} could not be updated")
                }
                rule.id
            }
        }
        return ruleId
    }

    override suspend fun applyStopProtecting(target: FeedbackTarget, updatedAt: Long): Boolean {
        var applied = false
        transactionRunner.run transaction@{
            if (sourceDao.find(target.sourcePackage) == null) return@transaction
            requireEvent(target)
            if (sourceDao.disable(target.sourcePackage, updatedAt) != 1) {
                throw IllegalStateException("Protected source ${target.sourcePackage} could not be disabled")
            }
            if (jumpEventDao.updateFeedback(target.eventId, "STOP_PROTECTING_SOURCE") != 1) {
                throw FeedbackEventNotFoundException(target.eventId)
            }
            applied = true
        }
        return applied
    }

    override suspend fun countBlockedEvents(source: String, target: String): Int =
        jumpEventDao.countBlocks(source, target)

    private suspend fun requireEvent(target: FeedbackTarget): JumpEventEntity {
        val event = jumpEventDao.find(target.eventId)
            ?: throw FeedbackEventNotFoundException(target.eventId)
        if (event.sourcePackage != target.sourcePackage || event.targetPackage != target.targetPackage) {
            throw FeedbackTargetMismatchException(target.eventId)
        }
        return event
    }
}
