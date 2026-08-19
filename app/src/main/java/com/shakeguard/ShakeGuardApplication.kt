package com.shakeguard

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.shakeguard.data.AppDatabase
import com.shakeguard.data.RoomFeedbackRepository
import com.shakeguard.data.RuleRepository
import com.shakeguard.data.RuleTransactionRunner
import com.shakeguard.data.SettingsStore
import com.shakeguard.feedback.FeedbackHandler
import com.shakeguard.feedback.FeedbackRepository
import com.shakeguard.feedback.OneTimeAllowanceStore
import com.shakeguard.notifications.ProtectionNotificationManager
import com.shakeguard.protection.EpochClock
import com.shakeguard.protection.MonotonicClock
import com.shakeguard.protection.SystemEpochClock
import com.shakeguard.protection.SystemMonotonicClock
import com.shakeguard.protection.ProtectionGate
import com.shakeguard.protection.ProtectionSettings
import com.shakeguard.protection.SettingsProtectionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class ShakeGuardDependencyGraph(
    private val databaseFactory: () -> AppDatabase,
    private val feedbackRepositoryFactory: (AppDatabase) -> FeedbackRepository = {
        RoomFeedbackRepository(it)
    },
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val epochClock: EpochClock = SystemEpochClock,
    private val settingsStoreFactory: () -> ProtectionSettings = { error("settings store is not configured") },
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ruleTransactionRunnerFactory: (AppDatabase) -> RuleTransactionRunner = { database ->
        RuleTransactionRunner { block -> database.withTransaction { block() } }
    },
) {
    val database: AppDatabase by lazy(databaseFactory)

    val ruleRepository: RuleRepository by lazy {
        RuleRepository(
            database.protectedSourceDao(),
            database.pairRuleDao(),
            database.jumpEventDao(),
            ruleTransactionRunnerFactory(database),
        )
    }

    val allowanceStore: OneTimeAllowanceStore by lazy {
        OneTimeAllowanceStore(monotonicClock)
    }

    val feedbackHandler: FeedbackHandler by lazy {
        FeedbackHandler(
            repository = feedbackRepositoryFactory(database),
            allowanceStore = allowanceStore,
            clock = epochClock,
        )
    }

    val settingsStore: ProtectionSettings by lazy(settingsStoreFactory)

    val protectionGate: ProtectionGate by lazy {
        SettingsProtectionGate(settingsStore, applicationScope)
    }
}

class ShakeGuardApplication : Application() {
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val dependencyGraph by lazy {
        ShakeGuardDependencyGraph(
            databaseFactory = {
                Room.databaseBuilder(this, AppDatabase::class.java, DATABASE_NAME)
                    .addMigrations(AppDatabase.MIGRATION_1_2)
                    .build()
            },
            settingsStoreFactory = { SettingsStore(this) },
            applicationScope = applicationScope,
        )
    }

    val database: AppDatabase
        get() = dependencyGraph.database

    val ruleRepository: RuleRepository
        get() = dependencyGraph.ruleRepository

    val allowanceStore: OneTimeAllowanceStore
        get() = dependencyGraph.allowanceStore

    val feedbackHandler: FeedbackHandler
        get() = dependencyGraph.feedbackHandler

    val settingsStore: ProtectionSettings
        get() = dependencyGraph.settingsStore

    val protectionGate: ProtectionGate
        get() = dependencyGraph.protectionGate

    val protectionNotificationManager: ProtectionNotificationManager by lazy {
        ProtectionNotificationManager(this)
    }

    private companion object {
        const val DATABASE_NAME = "shakeguard.db"
    }
}
