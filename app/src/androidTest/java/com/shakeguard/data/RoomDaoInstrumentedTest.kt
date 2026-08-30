package com.shakeguard.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shakeguard.feedback.FeedbackTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDaoInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun protectedSourceUpsertReplacesUserSettings() = runBlocking {
        val dao = database.protectedSourceDao()
        dao.upsert(source("news", windowMs = 5_000L, enabled = true, updatedAt = 10L))
        dao.upsert(source("news", windowMs = 8_000L, enabled = false, updatedAt = 20L))

        assertEquals(
            source("news", windowMs = 8_000L, enabled = false, updatedAt = 20L),
            dao.find("news"),
        )
    }

    @Test
    fun pairQueryReturnsOnlyEnabledExactSourceTargetMatches() = runBlocking {
        val dao = database.pairRuleDao()
        dao.insert(pair("news", "store", enabled = true))
        dao.insert(pair("news", "browser", enabled = true))
        dao.insert(pair("video", "store", enabled = true))
        dao.insert(pair("news", "store", enabled = false))

        val matches = dao.find("news", "store")

        assertEquals(1, matches.size)
        assertEquals("news", matches.single().sourcePackage)
        assertEquals("store", matches.single().targetPackage)
    }

    @Test
    fun recentEventsAreNewestFirstAndRespectLimit() = runBlocking {
        val dao = database.jumpEventDao()
        dao.insert(event("first", createdAt = 100L))
        dao.insert(event("second", createdAt = 300L))
        dao.insert(event("third", createdAt = 200L))

        assertEquals(
            listOf("second", "third"),
            dao.observeRecent(limit = 2).first().map { it.targetPackage },
        )
    }

    @Test
    fun cleanupDeletesOnlyEventsOlderThanThirtyDayBoundary() = runBlocking {
        val dao = database.jumpEventDao()
        val now = 4_000_000_000L
        val thirtyDaysMs = 30L * 24L * 60L * 60L * 1_000L
        val boundary = now - thirtyDaysMs
        dao.insert(event("older", createdAt = boundary - 1L))
        dao.insert(event("boundary", createdAt = boundary))
        dao.insert(event("newer", createdAt = boundary + 1L))

        dao.deleteBefore(boundary)

        val remaining = dao.observeRecent(limit = 10).first()
        assertNull(remaining.find { it.targetPackage == "older" })
        assertEquals(setOf("boundary", "newer"), remaining.map { it.targetPackage }.toSet())
    }

    @Test
    fun ruleManagementKeepsOppositeKindsAndMutatesOnlySelectedId() = runBlocking {
        val dao = database.pairRuleDao()
        val allowId = dao.insert(pair("news", "store", kind = "ALLOW", enabled = true))
        val blockId = dao.insert(pair("news", "store", kind = "BLOCK", enabled = true))

        assertEquals(
            listOf("BLOCK", "ALLOW"),
            dao.observeAll().first().map { it.kind },
        )
        assertEquals(1, dao.setEnabled(allowId, false, updatedAt = 40L))
        assertEquals(1, dao.delete(blockId))
        assertEquals(
            PairRuleEntity(
                id = allowId,
                sourcePackage = "news",
                targetPackage = "store",
                kind = "ALLOW",
                enabled = false,
                origin = "MANUAL",
                updatedAt = 40L,
            ),
            dao.observeAll().first().single(),
        )
    }

    @Test
    fun observeAllSourcesIncludesPausedSourcesInStableOrder() = runBlocking {
        val dao = database.protectedSourceDao()
        dao.upsert(source("video", windowMs = 2_000L, enabled = false, updatedAt = 22L, createdAt = 2L))
        dao.upsert(source("news", windowMs = 5_000L, enabled = true, updatedAt = 11L, createdAt = 1L))

        val sources = dao.observeAll().first()

        assertEquals(listOf("news", "video"), sources.map { it.packageName })
        assertFalse(sources.last().enabled)
        assertEquals(2_000L, sources.last().windowMs)
        assertEquals(2L, sources.last().createdAt)
        assertEquals(22L, sources.last().updatedAt)
    }

    @Test
    fun deleteAllRemovesOnlyJumpEvents() = runBlocking {
        val sourceDao = database.protectedSourceDao()
        sourceDao.upsert(source("news", windowMs = 5_000L, enabled = false, updatedAt = 10L, createdAt = 3L))
        val eventDao = database.jumpEventDao()
        eventDao.insert(event("store", createdAt = 100L))
        eventDao.insert(event("browser", createdAt = 101L))

        assertEquals(2, eventDao.deleteAll())
        assertTrue(eventDao.observeRecent(limit = 10).first().isEmpty())
        assertEquals("news", sourceDao.find("news")?.packageName)
        assertFalse(sourceDao.find("news")?.enabled ?: true)
    }

    @Test
    fun feedbackRepositoryFindsEventAndUpdatesFeedbackById() = runBlocking {
        val eventDao = database.jumpEventDao()
        val eventId = eventDao.insert(event("store", createdAt = 100L))
        val repository = RoomFeedbackRepository(database)

        assertEquals(
            FeedbackTarget(eventId, "news", "store"),
            repository.findFeedbackTarget(eventId),
        )
        repository.markAllowOnce(FeedbackTarget(eventId, "news", "store"))

        assertEquals("ALLOW_ONCE", eventDao.find(eventId)?.userFeedback)
        assertNull(repository.findFeedbackTarget(999L))
    }

    @Test
    fun allowPairFeedbackReusesDisabledRuleIdAndUpdatesEvent() = runBlocking {
        val eventDao = database.jumpEventDao()
        val pairDao = database.pairRuleDao()
        val eventId = eventDao.insert(event("store", createdAt = 100L))
        val existingId = pairDao.insert(pair("news", "store", kind = "ALLOW", enabled = false))
        val repository = RoomFeedbackRepository(database)
        val target = FeedbackTarget(eventId, "news", "store")

        val firstId = repository.applyAllowPair(target, updatedAt = 200L)
        val secondId = repository.applyAllowPair(target, updatedAt = 300L)

        assertEquals(existingId, firstId)
        assertEquals(firstId, secondId)
        assertEquals(1, pairDao.find("news", "store").size)
        assertEquals(
            PairRuleEntity(
                id = existingId,
                sourcePackage = "news",
                targetPackage = "store",
                kind = "ALLOW",
                enabled = true,
                origin = "FEEDBACK",
                updatedAt = 300L,
            ),
            pairDao.findByKind("news", "store", "ALLOW"),
        )
        assertEquals("ALLOW_PAIR", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun confirmAdFeedbackCreatesAndReusesBlockRuleId() = runBlocking {
        val eventDao = database.jumpEventDao()
        val pairDao = database.pairRuleDao()
        val eventId = eventDao.insert(event("store", createdAt = 100L))
        val repository = RoomFeedbackRepository(database)
        val target = FeedbackTarget(eventId, "news", "store")

        val firstId = repository.applyConfirmAd(target, updatedAt = 200L)
        val secondId = repository.applyConfirmAd(target, updatedAt = 300L)

        assertTrue(firstId > 0L)
        assertEquals(firstId, secondId)
        assertEquals(1, pairDao.find("news", "store").size)
        assertEquals(
            PairRuleEntity(
                id = firstId,
                sourcePackage = "news",
                targetPackage = "store",
                kind = "BLOCK",
                enabled = true,
                origin = "FEEDBACK",
                updatedAt = 300L,
            ),
            pairDao.findByKind("news", "store", "BLOCK"),
        )
        assertEquals("AD", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun stopProtectingDisablesSourceWithoutDroppingSettingsOrPairRules() = runBlocking {
        val sourceDao = database.protectedSourceDao()
        val pairDao = database.pairRuleDao()
        val eventDao = database.jumpEventDao()
        sourceDao.upsert(
            source(
                "news",
                windowMs = 8_000L,
                enabled = true,
                updatedAt = 10L,
                createdAt = 3L,
            ),
        )
        val pairId = pairDao.insert(pair("news", "store", kind = "BLOCK", enabled = true))
        val eventId = event("store", createdAt = 100L).let { eventDao.insert(it) }
        val repository = RoomFeedbackRepository(database)

        assertTrue(repository.applyStopProtecting(FeedbackTarget(eventId, "news", "store"), updatedAt = 55L))
        assertEquals(
            ProtectedSourceEntity(
                packageName = "news",
                enabled = false,
                windowMs = 8_000L,
                sourceLevelBlock = true,
                updatedAt = 55L,
                createdAt = 3L,
            ),
            sourceDao.find("news"),
        )
        assertEquals(pairId, pairDao.findByKind("news", "store", "BLOCK")?.id)
        assertEquals("STOP_PROTECTING_SOURCE", eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun stopProtectingMissingSourceLeavesEventUnchanged() = runBlocking {
        val eventDao = database.jumpEventDao()
        val eventId = eventDao.insert(event("store", createdAt = 100L))
        val repository = RoomFeedbackRepository(database)

        assertFalse(repository.applyStopProtecting(FeedbackTarget(eventId, "news", "store"), updatedAt = 55L))
        assertNull(eventDao.find(eventId)?.userFeedback)
    }

    @Test
    fun blockedEventCountIsExactForSourceTargetPair() = runBlocking {
        val eventDao = database.jumpEventDao()
        eventDao.insert(event("store", createdAt = 100L))
        eventDao.insert(event("store", createdAt = 101L, decision = "OBSERVE"))
        eventDao.insert(event("browser", createdAt = 102L))
        eventDao.insert(event("store", createdAt = 103L, sourcePackage = "video"))
        val repository = RoomFeedbackRepository(database)

        assertEquals(1, repository.countBlockedEvents("news", "store"))
        assertEquals(1, repository.countBlockedEvents("news", "browser"))
        assertEquals(1, repository.countBlockedEvents("video", "store"))
        assertEquals(0, repository.countBlockedEvents("news", "unknown"))
    }

    private fun source(
        packageName: String,
        windowMs: Long,
        enabled: Boolean,
        updatedAt: Long,
        createdAt: Long = updatedAt,
    ) = ProtectedSourceEntity(
        packageName = packageName,
        enabled = enabled,
        windowMs = windowMs,
        sourceLevelBlock = true,
        updatedAt = updatedAt,
        createdAt = createdAt,
    )

    private fun pair(
        source: String,
        target: String,
        kind: String = "BLOCK",
        enabled: Boolean,
    ) = PairRuleEntity(
        sourcePackage = source,
        targetPackage = target,
        kind = kind,
        enabled = enabled,
        origin = "MANUAL",
        updatedAt = 1L,
    )

    private fun event(
        target: String,
        createdAt: Long,
        sourcePackage: String = "news",
        decision: String = "BLOCK",
    ) = JumpEventEntity(
        sourcePackage = sourcePackage,
        targetPackage = target,
        elapsedMs = 100L,
        decision = decision,
        matchedRuleId = null,
        actionResult = if (decision == "BLOCK") "RETURNED" else "NONE",
        userFeedback = null,
        createdAt = createdAt,
    )
}
