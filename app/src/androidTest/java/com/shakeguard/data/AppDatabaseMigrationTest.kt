package com.shakeguard.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationFrom1To2PreservesSourceAndBackfillsCreatedAt() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO protected_sources
                    (packageName, enabled, windowMs, sourceLevelBlock, updatedAt)
                VALUES ('news', 1, 7000, 0, 1234)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query(
                "SELECT packageName, enabled, windowMs, sourceLevelBlock, createdAt, updatedAt " +
                    "FROM protected_sources WHERE packageName = 'news'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("news", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(7_000L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(1_234L, cursor.getLong(4))
                assertEquals(1_234L, cursor.getLong(5))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "shakeguard-migration-test"
    }
}
