package com.shakeguard.protection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

interface ProtectionEnabledStore {
    val enabled: Flow<Boolean>
}

interface ProtectionSettings : ProtectionEnabledStore {
    val windowMs: Flow<Long>
    val notifications: Flow<Boolean>
    suspend fun setEnabled(value: Boolean)
    suspend fun setWindowMs(value: Long)
    suspend fun setNotifications(value: Boolean)
}

enum class ProtectionGateState { NotReady, Enabled, Disabled }

interface ProtectionGate {
    val state: StateFlow<ProtectionGateState>
}

class SettingsProtectionGate(
    store: ProtectionEnabledStore,
    scope: CoroutineScope,
) : ProtectionGate {
    override val state: StateFlow<ProtectionGateState> = store.enabled
        .map { if (it) ProtectionGateState.Enabled else ProtectionGateState.Disabled }
        .stateIn(scope, SharingStarted.Eagerly, ProtectionGateState.NotReady)
}
