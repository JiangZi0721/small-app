package com.shakeguard

import com.shakeguard.accessibility.createProtectionCoordinator
import com.shakeguard.data.AppDatabase
import com.shakeguard.data.JumpEventEntity
import com.shakeguard.data.JumpEventDao
import com.shakeguard.data.PairRuleDao
import com.shakeguard.data.PairRuleEntity
import com.shakeguard.data.ProtectedSourceDao
import com.shakeguard.data.ProtectedSourceEntity
import com.shakeguard.data.RuleTransactionRunner
import com.shakeguard.feedback.FeedbackCommand
import com.shakeguard.feedback.FeedbackResult
import com.shakeguard.feedback.FeedbackRepository
import com.shakeguard.feedback.FeedbackTarget
import androidx.room.InvalidationTracker
import com.shakeguard.protection.EpochClock
import com.shakeguard.protection.BackActionExecutor
import com.shakeguard.protection.MonotonicClock
import com.shakeguard.protection.ProtectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeGuardApplicationTest {
    @Test
    fun dependencyGraphSharesSettingsStoreAndProtectionGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = FakeProtectionSettings()
        val graph = ShakeGuardDependencyGraph(
            databaseFactory = { FakeAppDatabase() },
            monotonicClock = FakeMonotonicClock(),
            epochClock = FakeEpochClock(7_000L),
            settingsStoreFactory = { settings },
            applicationScope = scope,
        )

        assertSame(graph.settingsStore, graph.settingsStore)
        assertSame(graph.protectionGate, graph.protectionGate)
        assertEquals(com.shakeguard.protection.ProtectionGateState.NotReady, graph.protectionGate.state.value)
        scope.cancel()
    }

    @Test
    fun dependencyGraphUsesTheProvidedRuleTransactionRunnerForSharedDatabase() = runBlocking {
        val database = FakeAppDatabase()
        var transactionCalls = 0
        val graph = ShakeGuardDependencyGraph(
            databaseFactory = { database },
            ruleTransactionRunnerFactory = {
                RuleTransactionRunner { block ->
                    transactionCalls += 1
                    block()
                }
            },
        )

        graph.ruleRepository.saveManagedRule(
            com.shakeguard.data.ManagedPairRule(
                id = 0L,
                sourcePackage = "news",
                targetPackage = "store",
                kind = com.shakeguard.protection.RuleKind.ALLOW,
                enabled = true,
                origin = "UI",
                updatedAt = 1L,
            ),
            updatedAt = 1L,
        )

        assertEquals(1, transactionCalls)
        assertSame(database, graph.database)
    }

    @Test
    fun dependencyGraphCreatesDatabaseLazilyAndSharesEveryDependency() = runBlocking {
        val database = FakeAppDatabase()
        val feedbackRepository = RecordingFeedbackRepository()
        var databaseCreations = 0
        var feedbackDatabase: AppDatabase? = null
        val graph = ShakeGuardDependencyGraph(
            databaseFactory = {
                databaseCreations += 1
                database
            },
            feedbackRepositoryFactory = { suppliedDatabase ->
                feedbackDatabase = suppliedDatabase
                feedbackRepository
            },
            monotonicClock = FakeMonotonicClock(),
            epochClock = FakeEpochClock(7_000L),
        )

        assertEquals(0, databaseCreations)
        assertSame(database, graph.database)
        assertSame(database, graph.database)
        assertEquals(1, databaseCreations)
        assertSame(graph.ruleRepository, graph.ruleRepository)
        assertSame(graph.allowanceStore, graph.allowanceStore)
        assertSame(graph.feedbackHandler, graph.feedbackHandler)
        assertSame(database, feedbackDatabase)

        assertTrue(graph.feedbackHandler.handle(com.shakeguard.feedback.FeedbackCommand.AllowOnce(7L)) is com.shakeguard.feedback.FeedbackResult.Applied)
        assertEquals(FeedbackTarget(7L, "news", "store"), feedbackRepository.markedTarget)
        assertTrue(graph.allowanceStore.consume("news", "store"))
    }

    @Test
    fun defaultGraphUsesRoomFeedbackRepositoryWithTheSharedDatabase() = runBlocking {
        val database = FakeAppDatabase()
        val graph = ShakeGuardDependencyGraph(
            databaseFactory = { database },
            monotonicClock = FakeMonotonicClock(),
            epochClock = FakeEpochClock(8_000L),
        )

        assertEquals(
            com.shakeguard.protection.ProtectedSource("news", windowMs = 5_000L, sourceLevelBlock = true),
            graph.ruleRepository.findSource("news"),
        )
        assertEquals(
            FeedbackResult.Applied(FeedbackCommand.AllowOnce(7L)),
            graph.feedbackHandler.handle(FeedbackCommand.AllowOnce(7L)),
        )
        assertEquals("ALLOW_ONCE", database.jumpEventDao.find(7L)?.userFeedback)

        assertTrue(graph.ruleRepository.setSourceEnabled("news", false, updatedAt = 55L))
        assertEquals(false, graph.ruleRepository.findSource("news")?.enabled)
        assertEquals(55L, database.protectedSourceDao().find("news")?.updatedAt)
        assertEquals(listOf(7L), graph.ruleRepository.observeRecentEvents().first().map { it.id })
        assertEquals(1, graph.ruleRepository.clearAllEvents())
        assertTrue(graph.ruleRepository.observeRecentEvents().first().isEmpty())
    }

    @Test
    fun serviceCoordinatorFactoryUsesApplicationRuleRepositoryAndAllowanceStore() {
        val database = FakeAppDatabase()
        val graph = ShakeGuardDependencyGraph(
            databaseFactory = { database },
            monotonicClock = FakeMonotonicClock(),
            epochClock = FakeEpochClock(9_000L),
        )
        val backAction = BackActionExecutor { _, _ -> true }

        val coordinator = createProtectionCoordinator(
            ruleRepository = graph.ruleRepository,
            allowanceStore = graph.allowanceStore,
            excludedPackages = emptySet(),
            backAction = backAction,
            clock = FakeMonotonicClock(),
        )

        runBlocking {
            coordinator.onForeground("news")
            graph.allowanceStore.grant("news", "store")
            val outcome = coordinator.onForeground("store")
            assertEquals("one-time allowance", outcome?.decision?.reason)
            assertSame(database.jumpEventDao, database.jumpEventDao)
        }
    }

    @Test
    fun manifestRegistersTheProcessApplication() {
        val manifest = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { directory ->
                listOf(
                    File(directory, "app/src/main/AndroidManifest.xml"),
                    File(directory, "src/main/AndroidManifest.xml"),
                )
            }
            .flatten()
            .firstOrNull(File::isFile)
            ?: error("AndroidManifest.xml was not found")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory
            .newDocumentBuilder()
            .parse(manifest)
        val application = document.getElementsByTagName("application").item(0)

        assertEquals(
            ".ShakeGuardApplication",
            application.attributes.getNamedItemNS(
                "http://schemas.android.com/apk/res/android",
                "name",
            ).nodeValue!!,
        )

        val permissions = document.getElementsByTagName("uses-permission")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it).attributes.getNamedItemNS(
                "http://schemas.android.com/apk/res/android",
                "name",
            )?.nodeValue } }
        assertTrue(permissions.contains("android.permission.QUERY_ALL_PACKAGES"))
    }

    private class RecordingFeedbackRepository : FeedbackRepository {
        var markedTarget: FeedbackTarget? = null

        override suspend fun findFeedbackTarget(eventId: Long): FeedbackTarget? =
            FeedbackTarget(eventId, "news", "store").takeIf { eventId == 7L }

        override suspend fun markAllowOnce(target: FeedbackTarget) {
            markedTarget = target
        }

        override suspend fun applyAllowPair(target: FeedbackTarget, updatedAt: Long): Long = 1L
        override suspend fun applyConfirmAd(target: FeedbackTarget, updatedAt: Long): Long = 2L
        override suspend fun applyStopProtecting(target: FeedbackTarget, updatedAt: Long): Boolean = true
        override suspend fun countBlockedEvents(source: String, target: String): Int = 0
    }

    private class FakeProtectionSettings : ProtectionSettings {
        private val values = MutableSharedFlow<Boolean>(replay = 0)
        override val enabled: Flow<Boolean> = values
        override val windowMs: Flow<Long> = flowOf(5_000L)
        override val notifications: Flow<Boolean> = flowOf(false)
        override suspend fun setEnabled(value: Boolean) = Unit
        override suspend fun setWindowMs(value: Long) = Unit
        override suspend fun setNotifications(value: Boolean) = Unit
    }

    private class FakeAppDatabase : AppDatabase() {
        private val protectedSourceDao = InMemoryProtectedSourceDao()
        private val pairRuleDao = EmptyPairRuleDao()
        val jumpEventDao = InMemoryJumpEventDao()

        override fun protectedSourceDao(): ProtectedSourceDao = protectedSourceDao
        override fun pairRuleDao(): PairRuleDao = pairRuleDao
        override fun jumpEventDao(): JumpEventDao = jumpEventDao
        override fun clearAllTables() = Unit
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)
    }

    private class InMemoryProtectedSourceDao : ProtectedSourceDao {
        private val values = mutableMapOf(
            "news" to ProtectedSourceEntity(
                packageName = "news",
                enabled = true,
                windowMs = 5_000L,
                sourceLevelBlock = true,
                updatedAt = 1L,
                createdAt = 1L,
            ),
        )

        override fun observeEnabled(): Flow<List<ProtectedSourceEntity>> = flowOf(values.values.filter { it.enabled })
        override fun observeAll(): Flow<List<ProtectedSourceEntity>> = flowOf(values.values.sortedWith(compareBy({ it.createdAt }, { it.packageName })))
        override suspend fun find(packageName: String): ProtectedSourceEntity? = values[packageName]
        override suspend fun disable(packageName: String, updatedAt: Long): Int = 0
        override suspend fun setEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Int {
            val source = values[packageName] ?: return 0
            values[packageName] = source.copy(enabled = enabled, updatedAt = updatedAt)
            return 1
        }
        override suspend fun upsert(source: ProtectedSourceEntity) {
            values[source.packageName] = source
        }
    }

    private class EmptyPairRuleDao : PairRuleDao {
        override fun observeAll(): Flow<List<PairRuleEntity>> = flowOf(emptyList())
        override suspend fun find(source: String, target: String): List<PairRuleEntity> = emptyList()
        override suspend fun findByKind(source: String, target: String, kind: String): PairRuleEntity? = null
        override suspend fun update(rule: PairRuleEntity): Int = 0
        override suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int = 0
        override suspend fun delete(id: Long): Int = 0
        override suspend fun insert(rule: PairRuleEntity): Long = 1L
    }

    private class InMemoryJumpEventDao : JumpEventDao {
        private val values = mutableMapOf(
            7L to JumpEventEntity(
                id = 7L,
                sourcePackage = "news",
                targetPackage = "store",
                elapsedMs = 100L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = 1L,
            ),
        )

        override suspend fun find(id: Long): JumpEventEntity? = values[id]
        override suspend fun updateFeedback(id: Long, feedback: String): Int {
            val event = values[id] ?: return 0
            values[id] = event.copy(userFeedback = feedback)
            return 1
        }
        override suspend fun countBlocks(source: String, target: String): Int = 0
        override fun observeRecent(limit: Int): Flow<List<JumpEventEntity>> =
            flowOf(values.values.sortedByDescending { it.createdAt }.take(limit))
        override suspend fun insert(event: JumpEventEntity): Long {
            values[event.id] = event
            return event.id
        }
        override suspend fun deleteBefore(before: Long) = Unit
        override suspend fun deleteAll(): Int {
            val count = values.size
            values.clear()
            return count
        }
    }

    private class FakeMonotonicClock : MonotonicClock {
        override fun elapsedRealtime(): Long = 1_000L
    }

    private class FakeEpochClock(private val now: Long) : EpochClock {
        override fun currentTimeMillis(): Long = now
    }
}
