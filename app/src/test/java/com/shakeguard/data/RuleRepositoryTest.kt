package com.shakeguard.data

import com.shakeguard.feedback.FeedbackEventNotFoundException
import com.shakeguard.feedback.FeedbackTarget
import com.shakeguard.feedback.FeedbackTargetMismatchException
import com.shakeguard.protection.ProtectedSource
import com.shakeguard.protection.PairRule
import com.shakeguard.protection.RuleKind
import com.shakeguard.protection.ActionResult
import com.shakeguard.protection.Decision
import com.shakeguard.protection.DecisionKind
import com.shakeguard.protection.EpochClock
import com.shakeguard.protection.ForegroundTransition
import com.shakeguard.protection.ProtectionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRepositoryTest {
    @Test
    fun managedRulesIncludeDisabledAndPreserveOppositeKinds() = runBlocking {
        val pairDao = FakePairRuleDao(
            listOf(
                PairRuleEntity(1L, "news", "store", "ALLOW", true, "MANUAL", 30L),
                PairRuleEntity(2L, "news", "store", "BLOCK", false, "FEEDBACK", 20L),
                PairRuleEntity(3L, "news", "store", "UNKNOWN", true, "MANUAL", 10L),
            ),
        )
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), pairDao, FakeJumpEventDao())

        assertEquals(
            listOf(
                ManagedPairRule(1L, "news", "store", RuleKind.ALLOW, true, "MANUAL", 30L),
                ManagedPairRule(2L, "news", "store", RuleKind.BLOCK, false, "FEEDBACK", 20L),
            ),
            repository.observeAllRules().first(),
        )
    }

    @Test
    fun ruleMutationsOnlyAffectSelectedId() = runBlocking {
        val pairDao = FakePairRuleDao(
            listOf(
                PairRuleEntity(1L, "news", "store", "ALLOW", true, "MANUAL", 10L),
                PairRuleEntity(2L, "news", "store", "BLOCK", true, "MANUAL", 11L),
            ),
        )
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), pairDao, FakeJumpEventDao())

        assertTrue(repository.setRuleEnabled(1L, false, 20L))
        assertTrue(repository.deleteRule(2L))
        assertEquals(false, pairDao.all().single { it.id == 1L }.enabled)
        assertEquals(20L, pairDao.all().single { it.id == 1L }.updatedAt)
        assertTrue(pairDao.all().none { it.id == 2L })
    }

    @Test
    fun savingSameKindKeepsStableIdAndOppositeKind() = runBlocking {
        val pairDao = FakePairRuleDao(
            listOf(PairRuleEntity(2L, "news", "store", "BLOCK", true, "MANUAL", 11L)),
        )
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), pairDao, FakeJumpEventDao())
        val allow = ManagedPairRule(0L, "news", "store", RuleKind.ALLOW, true, "UI", 30L)

        val firstId = repository.saveManagedRule(allow, 30L)
        val secondId = repository.saveManagedRule(allow.copy(enabled = false), 40L)

        assertEquals(firstId, secondId)
        assertEquals(setOf("ALLOW", "BLOCK"), pairDao.all().map { it.kind }.toSet())
        assertEquals(false, pairDao.all().single { it.kind == "ALLOW" }.enabled)
    }

    @Test
    fun concurrentSameKindSavesReturnOneStableId() = runBlocking {
        val pairDao = FakePairRuleDao()
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), pairDao, FakeJumpEventDao())
        val rule = ManagedPairRule(0L, "news", "store", RuleKind.ALLOW, true, "UI", 1L)

        val ids = coroutineScope {
            listOf(
                async(Dispatchers.Default) { repository.saveManagedRule(rule, 10L) },
                async(Dispatchers.Default) { repository.saveManagedRule(rule, 11L) },
            ).awaitAll()
        }

        assertEquals(ids[0], ids[1])
        assertEquals(1, pairDao.all().size)
    }

    private fun eventForUi(
        target: String,
        createdAt: Long,
        decision: String = "BLOCK",
        actionResult: String = "RETURNED",
    ) = JumpEventEntity(
        sourcePackage = "news",
        targetPackage = target,
        elapsedMs = 100L,
        decision = decision,
        matchedRuleId = null,
        actionResult = actionResult,
        userFeedback = null,
        createdAt = createdAt,
    )

    @Test
    fun managedSourcesIncludePausedSourcesAndPreserveAllSettings() = runBlocking {
        val sourceDao = FakeProtectedSourceDao(
            listOf(
                ProtectedSourceEntity("video", false, 2_000L, false, 22L, 2L),
                ProtectedSourceEntity("news", true, 5_000L, true, 11L, 1L),
            ),
        )
        val repository = RuleRepository(sourceDao, FakePairRuleDao(), FakeJumpEventDao())

        assertEquals(
            listOf(
                ManagedSource("news", true, 5_000L, true, 1L, 11L),
                ManagedSource("video", false, 2_000L, false, 2L, 22L),
            ),
            repository.observeAllSources().first(),
        )
    }

    @Test
    fun recentEventsAreNewestFirstAndLimitedToOneHundred() = runBlocking {
        val eventDao = FakeJumpEventDao()
        repeat(101) { index ->
            eventDao.insert(
                JumpEventEntity(
                    sourcePackage = "news",
                    targetPackage = "target-$index",
                    elapsedMs = index.toLong(),
                    decision = "BLOCK",
                    matchedRuleId = null,
                    actionResult = "RETURNED",
                    userFeedback = null,
                    createdAt = index.toLong(),
                ),
            )
        }
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), FakePairRuleDao(), eventDao)

        val events = repository.observeRecentEvents().first()

        assertEquals(100, events.size)
        assertEquals(100L, events.first().createdAt)
        assertEquals(1L, events.last().createdAt)
    }

    @Test
    fun clearAllEventsReturnsDeletedCountWithoutDeletingSources() = runBlocking {
        val sourceDao = FakeProtectedSourceDao(
            listOf(ProtectedSourceEntity("news", true, 5_000L, true, 10L, 3L)),
        )
        val eventDao = FakeJumpEventDao()
        eventDao.insert(eventForUi("store", createdAt = 1L))
        eventDao.insert(eventForUi("browser", createdAt = 2L))
        val repository = RuleRepository(sourceDao, FakePairRuleDao(), eventDao)

        assertEquals(2, repository.clearAllEvents())
        assertEquals(0, repository.observeRecentEvents().first().size)
        assertEquals(1, repository.observeAllSources().first().size)
    }

    @Test
    fun setSourceEnabledChangesOnlyEnabledAndUpdatedAt() = runBlocking {
        val sourceDao = FakeProtectedSourceDao(
            listOf(ProtectedSourceEntity("news", true, 7_000L, false, 10L, 3L)),
        )
        val repository = RuleRepository(sourceDao, FakePairRuleDao(), FakeJumpEventDao())

        assertTrue(repository.setSourceEnabled("news", false, updatedAt = 55L))
        assertEquals(
            ProtectedSourceEntity("news", false, 7_000L, false, 55L, 3L),
            sourceDao.find("news"),
        )
        assertFalse(repository.setSourceEnabled("missing", true, updatedAt = 56L))
    }

    @Test
    fun saveManagedSourcePreservesExistingCreatedAt() = runBlocking {
        val sourceDao = FakeProtectedSourceDao(
            listOf(ProtectedSourceEntity("news", true, 5_000L, true, 10L, 3L)),
        )
        val repository = RuleRepository(sourceDao, FakePairRuleDao(), FakeJumpEventDao())

        repository.saveManagedSource(
            ManagedSource("news", false, 8_000L, false, createdAt = 99L, updatedAt = 0L),
            updatedAt = 55L,
        )

        assertEquals(3L, sourceDao.find("news")?.createdAt)
        assertEquals(55L, sourceDao.find("news")?.updatedAt)
        assertFalse(sourceDao.find("news")?.enabled ?: true)
    }

    @Test
    fun corruptUiEventEnumsAreSkipped() = runBlocking {
        val eventDao = FakeJumpEventDao()
        eventDao.insert(eventForUi("valid", createdAt = 2L))
        eventDao.insert(eventForUi("bad-decision", createdAt = 3L, decision = "UNKNOWN"))
        eventDao.insert(eventForUi("bad-action", createdAt = 4L, actionResult = "UNKNOWN"))
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), FakePairRuleDao(), eventDao)

        assertEquals(listOf("valid"), repository.observeRecentEvents().first().map { it.targetPackage })
    }

    @Test
    fun feedbackRepositoryFindsExactEventTargetById() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = FakePairRuleDao(),
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        assertEquals(
            FeedbackTarget(eventId, "news", "store"),
            repository.findFeedbackTarget(eventId),
        )
        assertNull(repository.findFeedbackTarget(999L))
    }

    @Test
    fun markAllowOnceUpdatesOnlyTheTargetEvent() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = FakePairRuleDao(),
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        repository.markAllowOnce(FeedbackTarget(eventId, "news", "store"))

        assertEquals("ALLOW_ONCE", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun markAllowOnceFailsClearlyWhenEventIsMissing() {
        val eventDao = FakeJumpEventDao()
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = FakePairRuleDao(),
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        val error = assertThrows(FeedbackEventNotFoundException::class.java) {
            runBlocking {
                repository.markAllowOnce(FeedbackTarget(999L, "news", "store"))
            }
        }

        assertEquals("Feedback event 999 was not found", error.message)
    }

    @Test
    fun applyAllowPairIsIdempotentForExactSourceTarget() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val pairDao = FakePairRuleDao()
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = pairDao,
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )
        val target = FeedbackTarget(eventId, "news", "store")

        val firstId = repository.applyAllowPair(target, updatedAt = 100L)
        val secondId = repository.applyAllowPair(target, updatedAt = 200L)

        assertEquals(firstId, secondId)
        assertEquals(1, pairDao.all().size)
        assertEquals(
            PairRuleEntity(
                id = firstId,
                sourcePackage = "news",
                targetPackage = "store",
                kind = "ALLOW",
                enabled = true,
                origin = "FEEDBACK",
                updatedAt = 200L,
            ),
            pairDao.all().single(),
        )
        assertEquals("ALLOW_PAIR", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun applyConfirmAdIsIdempotentForExactSourceTarget() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val pairDao = FakePairRuleDao()
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = pairDao,
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )
        val target = FeedbackTarget(eventId, "news", "store")

        val firstId = repository.applyConfirmAd(target, updatedAt = 300L)
        val secondId = repository.applyConfirmAd(target, updatedAt = 400L)

        assertEquals(firstId, secondId)
        assertEquals(1, pairDao.all().size)
        assertEquals(
            PairRuleEntity(
                id = firstId,
                sourcePackage = "news",
                targetPackage = "store",
                kind = "BLOCK",
                enabled = true,
                origin = "FEEDBACK",
                updatedAt = 400L,
            ),
            pairDao.all().single(),
        )
        assertEquals("AD", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun stopProtectingDisablesSourceAndPreservesSourceSettings() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val sourceDao = FakeProtectedSourceDao(
            listOf(
                ProtectedSourceEntity(
                    packageName = "news",
                    enabled = true,
                    windowMs = 8_000L,
                    sourceLevelBlock = false,
                    updatedAt = 10L,
                    createdAt = 3L,
                ),
            ),
        )
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = sourceDao,
            pairRuleDao = FakePairRuleDao(),
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        assertTrue(repository.applyStopProtecting(FeedbackTarget(eventId, "news", "store"), updatedAt = 55L))
        assertEquals(
            ProtectedSourceEntity(
                packageName = "news",
                enabled = false,
                windowMs = 8_000L,
                sourceLevelBlock = false,
                updatedAt = 55L,
                createdAt = 3L,
            ),
            sourceDao.find("news"),
        )
        assertEquals("STOP_PROTECTING_SOURCE", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun stopProtectingReturnsFalseAndLeavesEventUntouchedWhenSourceIsMissing() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = FakePairRuleDao(),
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        assertFalse(repository.applyStopProtecting(FeedbackTarget(eventId, "news", "store"), updatedAt = 55L))
        assertNull(eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun countBlockedEventsUsesExactSourceTargetPair() = runBlocking {
        val eventDao = FakeJumpEventDao()
        listOf(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 100L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 1L,
            ),
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 100L,
                decision = "OBSERVE",
                matchedRuleId = null,
                actionResult = "NONE",
                userFeedback = null,
                createdAt = 2L,
            ),
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "browser",
                elapsedMs = 100L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 3L,
            ),
            JumpEventEntity(
                sourcePackage = "video",
                targetPackage = "store",
                elapsedMs = 100L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 4L,
            ),
        ).forEach { eventDao.insert(it) }
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = FakePairRuleDao(),
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        assertEquals(1, repository.countBlockedEvents("news", "store"))
        assertEquals(1, repository.countBlockedEvents("news", "browser"))
        assertEquals(1, repository.countBlockedEvents("video", "store"))
        assertEquals(0, repository.countBlockedEvents("news", "unknown"))
    }

    @Test
    fun pairFeedbackFailsWithoutCreatingRuleWhenEventIsMissing() = runBlocking {
        val pairDao = FakePairRuleDao()
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = pairDao,
            jumpEventDao = FakeJumpEventDao(),
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )
        val target = FeedbackTarget(999L, "news", "store")

        assertThrows(FeedbackEventNotFoundException::class.java) {
            runBlocking { repository.applyAllowPair(target, updatedAt = 55L) }
        }
        assertThrows(FeedbackEventNotFoundException::class.java) {
            runBlocking { repository.applyConfirmAd(target, updatedAt = 55L) }
        }
        assertEquals(emptyList<PairRuleEntity>(), pairDao.all())
    }

    @Test
    fun pairFeedbackRejectsTargetMismatchWithoutChangingEventOrRules() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val pairDao = FakePairRuleDao()
        val eventId = eventDao.insert(
            JumpEventEntity(
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
        )
        val repository = RoomFeedbackRepository(
            sourceDao = FakeProtectedSourceDao(emptyList()),
            pairRuleDao = pairDao,
            jumpEventDao = eventDao,
            transactionRunner = FeedbackTransactionRunner { block -> block() },
        )

        assertThrows(FeedbackTargetMismatchException::class.java) {
            runBlocking {
                repository.applyAllowPair(FeedbackTarget(eventId, "news", "browser"), updatedAt = 55L)
            }
        }
        assertNull(eventDao.find(eventId)?.userFeedback)
        assertEquals(emptyList<PairRuleEntity>(), pairDao.all())
    }

    @Test
    fun enabledSourcesAreMappedToProtectionDomain() = runBlocking {
        val sourceDao = FakeProtectedSourceDao(
            listOf(
                ProtectedSourceEntity("news", true, 5_000L, true, 10L),
                ProtectedSourceEntity("video", true, 2_000L, false, 11L),
            ),
        )
        val repository = RuleRepository(sourceDao, FakePairRuleDao(), FakeJumpEventDao())

        assertEquals(
            listOf(
                ProtectedSource("news", windowMs = 5_000L, sourceLevelBlock = true),
                ProtectedSource("video", windowMs = 2_000L, sourceLevelBlock = false),
            ),
            repository.observeEnabledSources().first(),
        )
    }

    @Test
    fun sourceCanBeSavedWithItsProtectionSettings() = runBlocking {
        val sourceDao = FakeProtectedSourceDao(emptyList())
        val repository = RuleRepository(sourceDao, FakePairRuleDao(), FakeJumpEventDao())
        val source = ProtectedSource("news", enabled = false, windowMs = 7_000L, sourceLevelBlock = false)

        repository.saveSource(source, updatedAt = 42L)

        assertEquals(source, repository.findSource("news"))
    }

    @Test
    fun corruptPairRuleIsIgnoredWhileValidRulesRemainReadable() = runBlocking {
        val pairDao = FakePairRuleDao(
            listOf(
                PairRuleEntity(1L, "news", "store", "BLOCK", true, "MANUAL", 1L),
                PairRuleEntity(2L, "news", "store", "NOT_A_KIND", true, "MANUAL", 2L),
            ),
        )
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), pairDao, FakeJumpEventDao())

        assertEquals(
            listOf(PairRule(1L, "news", "store", RuleKind.BLOCK)),
            repository.findPairRules("news", "store"),
        )
    }

    @Test
    fun roomRecorderPersistsExplainableProtectionOutcome() = runBlocking {
        val eventDao = FakeJumpEventDao()
        val repository = RuleRepository(FakeProtectedSourceDao(emptyList()), FakePairRuleDao(), eventDao)
        val recorder = RoomProtectionEventRecorder(repository, EpochClock { 9_999L })

        val eventId = recorder.record(
            ProtectionRecord(
                ForegroundTransition("news", "store", 120L),
                Decision(DecisionKind.BLOCK, "source-target rule", ruleId = 7L),
                ActionResult.RETURN_FAILED,
            ),
        )

        assertEquals(42L, eventId)
        assertEquals(
            JumpEventEntity(
                id = 42L,
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 120L,
                decision = "BLOCK",
                matchedRuleId = 7L,
                actionResult = "RETURN_FAILED",
                userFeedback = null,
                createdAt = 9_999L,
            ),
            eventDao.inserted.single(),
        )
    }

    private class FakeProtectedSourceDao(initial: List<ProtectedSourceEntity>) : ProtectedSourceDao {
        private val values = initial.toMutableList()
        override fun observeEnabled(): Flow<List<ProtectedSourceEntity>> = flowOf(values.filter { it.enabled })
        override fun observeAll(): Flow<List<ProtectedSourceEntity>> = flowOf(values.sortedWith(compareBy({ it.createdAt }, { it.packageName })))
        override suspend fun find(packageName: String): ProtectedSourceEntity? = values.find { it.packageName == packageName }
        override suspend fun disable(packageName: String, updatedAt: Long): Int {
            val index = values.indexOfFirst { it.packageName == packageName }
            if (index < 0) return 0
            values[index] = values[index].copy(enabled = false, updatedAt = updatedAt)
            return 1
        }
        override suspend fun setEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Int {
            val index = values.indexOfFirst { it.packageName == packageName }
            if (index < 0) return 0
            values[index] = values[index].copy(enabled = enabled, updatedAt = updatedAt)
            return 1
        }
        override suspend fun upsert(source: ProtectedSourceEntity) {
            values.removeAll { it.packageName == source.packageName }
            values += source
        }
    }

    private class FakePairRuleDao(initial: List<PairRuleEntity> = emptyList()) : PairRuleDao {
        private val values = initial.toMutableList()
        override fun observeAll(): Flow<List<PairRuleEntity>> =
            flowOf(values.sortedWith(compareByDescending<PairRuleEntity> { it.updatedAt }.thenByDescending { it.id }))
        override suspend fun find(source: String, target: String): List<PairRuleEntity> =
            values.filter { it.sourcePackage == source && it.targetPackage == target && it.enabled }

        override suspend fun findByKind(source: String, target: String, kind: String): PairRuleEntity? =
            values.firstOrNull {
                it.sourcePackage == source && it.targetPackage == target && it.kind == kind
            }

        override suspend fun update(rule: PairRuleEntity): Int {
            val index = values.indexOfFirst { it.id == rule.id }
            if (index < 0) return 0
            values[index] = rule
            return 1
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int {
            val index = values.indexOfFirst { it.id == id }
            if (index < 0) return 0
            values[index] = values[index].copy(enabled = enabled, updatedAt = updatedAt)
            return 1
        }

        override suspend fun delete(id: Long): Int {
            val index = values.indexOfFirst { it.id == id }
            if (index < 0) return 0
            values.removeAt(index)
            return 1
        }

        override suspend fun insert(rule: PairRuleEntity): Long {
            val id = if (rule.id == 0L) (values.maxOfOrNull { it.id } ?: 0L) + 1L else rule.id
            values += rule.copy(id = id)
            return id
        }

        fun all(): List<PairRuleEntity> = values.toList()
    }

    private class FakeJumpEventDao(private val generatedId: Long = 42L) : JumpEventDao {
        val inserted = mutableListOf<JumpEventEntity>()
        private val values = mutableListOf<JumpEventEntity>()
        private var nextGeneratedId = generatedId
        override suspend fun find(id: Long): JumpEventEntity? = values.find { it.id == id }
        override suspend fun updateFeedback(id: Long, feedback: String): Int {
            val index = values.indexOfFirst { it.id == id }
            if (index < 0) return 0
            val updated = values[index].copy(userFeedback = feedback)
            values[index] = updated
            inserted.replaceAll { event -> if (event.id == id) updated else event }
            return 1
        }
        override suspend fun countBlocks(source: String, target: String): Int =
            values.count {
                it.sourcePackage == source && it.targetPackage == target && it.decision == "BLOCK"
            }
        override fun observeRecent(limit: Int): Flow<List<JumpEventEntity>> = flowOf(values.sortedByDescending { it.createdAt }.take(limit))
        override suspend fun insert(event: JumpEventEntity): Long {
            val id = if (event.id == 0L) nextGeneratedId++ else event.id
            val persisted = event.copy(id = id)
            values += persisted
            inserted += persisted
            return id
        }
        override suspend fun deleteBefore(before: Long) = Unit
        override suspend fun deleteAll(): Int {
            val count = values.size
            values.clear()
            inserted.clear()
            return count
        }

    }
}
