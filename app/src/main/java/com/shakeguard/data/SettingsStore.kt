package com.shakeguard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.shakeguard.protection.ProtectionSettings

private val Context.shakeGuardDataStore by preferencesDataStore(name = "shakeguard_settings")

class SettingsStore(private val context: Context) : ProtectionSettings {
    private val protectionEnabled = booleanPreferencesKey("protection_enabled")
    private val defaultWindowMs = longPreferencesKey("default_window_ms")
    private val notificationsEnabled = booleanPreferencesKey("notifications_enabled")

    override val enabled: Flow<Boolean> = context.shakeGuardDataStore.data.map { it[protectionEnabled] ?: true }
    override val windowMs: Flow<Long> = context.shakeGuardDataStore.data.map { it[defaultWindowMs] ?: 5_000L }
    override val notifications: Flow<Boolean> = context.shakeGuardDataStore.data.map { it[notificationsEnabled] ?: false }

    override suspend fun setEnabled(value: Boolean) {
        context.shakeGuardDataStore.edit { it[protectionEnabled] = value }
    }

    override suspend fun setWindowMs(value: Long) {
        context.shakeGuardDataStore.edit { it[defaultWindowMs] = value.coerceIn(1_000L, 30_000L) }
    }

    override suspend fun setNotifications(value: Boolean) {
        context.shakeGuardDataStore.edit { it[notificationsEnabled] = value }
    }
}
