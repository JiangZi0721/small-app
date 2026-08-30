package com.shakeguard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectedSourceDao {
    @Query("SELECT * FROM protected_sources WHERE enabled = 1")
    fun observeEnabled(): Flow<List<ProtectedSourceEntity>>

    @Query("SELECT * FROM protected_sources ORDER BY createdAt ASC, packageName ASC")
    fun observeAll(): Flow<List<ProtectedSourceEntity>>

    @Query("SELECT * FROM protected_sources WHERE packageName = :packageName LIMIT 1")
    suspend fun find(packageName: String): ProtectedSourceEntity?

    @Query("UPDATE protected_sources SET enabled = 0, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun disable(packageName: String, updatedAt: Long): Int

    @Query("UPDATE protected_sources SET enabled = :enabled, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: ProtectedSourceEntity)
}

@Dao
interface PairRuleDao {
    @Query("SELECT * FROM pair_rules ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<PairRuleEntity>>

    @Query("SELECT * FROM pair_rules WHERE sourcePackage = :source AND targetPackage = :target AND enabled = 1")
    suspend fun find(source: String, target: String): List<PairRuleEntity>

    @Query(
        "SELECT * FROM pair_rules " +
            "WHERE sourcePackage = :source AND targetPackage = :target AND kind = :kind " +
            "LIMIT 1",
    )
    suspend fun findByKind(source: String, target: String, kind: String): PairRuleEntity?

    @Update
    suspend fun update(rule: PairRuleEntity): Int

    @Query("UPDATE pair_rules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM pair_rules WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Insert
    suspend fun insert(rule: PairRuleEntity): Long
}

@Dao
interface JumpEventDao {
    @Query("SELECT * FROM jump_events WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): JumpEventEntity?

    @Query("UPDATE jump_events SET userFeedback = :feedback WHERE id = :id")
    suspend fun updateFeedback(id: Long, feedback: String): Int

    @Query(
        "SELECT COUNT(*) FROM jump_events " +
            "WHERE sourcePackage = :source AND targetPackage = :target AND decision = 'BLOCK'",
    )
    suspend fun countBlocks(source: String, target: String): Int

    @Query("SELECT * FROM jump_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<JumpEventEntity>>

    @Insert
    suspend fun insert(event: JumpEventEntity): Long

    @Query("DELETE FROM jump_events WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM jump_events")
    suspend fun deleteAll(): Int
}
