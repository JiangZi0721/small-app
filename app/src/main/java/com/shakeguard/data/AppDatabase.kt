package com.shakeguard.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProtectedSourceEntity::class, PairRuleEntity::class, JumpEventEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun protectedSourceDao(): ProtectedSourceDao
    abstract fun pairRuleDao(): PairRuleDao
    abstract fun jumpEventDao(): JumpEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE protected_sources " +
                        "ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("UPDATE protected_sources SET createdAt = updatedAt")
            }
        }
    }
}
