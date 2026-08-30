package com.shakeguard.data

import com.shakeguard.protection.EpochClock
import com.shakeguard.protection.ProtectionEventRecorder
import com.shakeguard.protection.ProtectionRecord
import com.shakeguard.protection.SystemEpochClock

class RoomProtectionEventRecorder(
    private val repository: RuleRepository,
    private val clock: EpochClock = SystemEpochClock,
) : ProtectionEventRecorder {
    override suspend fun record(record: ProtectionRecord): Long =
        repository.recordEvent(
            JumpEventEntity(
                sourcePackage = record.transition.sourcePackage,
                targetPackage = record.transition.targetPackage,
                elapsedMs = record.transition.elapsedMs,
                decision = record.decision.kind.name,
                matchedRuleId = record.decision.ruleId,
                actionResult = record.actionResult.name,
                userFeedback = null,
                createdAt = clock.currentTimeMillis(),
            ),
        )
}
