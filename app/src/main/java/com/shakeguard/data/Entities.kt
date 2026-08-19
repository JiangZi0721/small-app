package com.shakeguard.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "protected_sources")
data class ProtectedSourceEntity(
    @PrimaryKey val packageName: String,
    val enabled: Boolean,
    val windowMs: Long,
    val sourceLevelBlock: Boolean,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = updatedAt,
)

@Entity(tableName = "pair_rules")
data class PairRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val targetPackage: String,
    val kind: String,
    val enabled: Boolean,
    val origin: String,
    val updatedAt: Long,
)

@Entity(tableName = "jump_events")
data class JumpEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePackage: String,
    val targetPackage: String,
    val elapsedMs: Long,
    val decision: String,
    val matchedRuleId: Long?,
    val actionResult: String,
    val userFeedback: String?,
    val createdAt: Long,
)
