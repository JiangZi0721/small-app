package com.shakeguard.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakeguard.data.ManagedSource
import com.shakeguard.protection.ProtectionGate
import com.shakeguard.protection.ProtectionGateState
import com.shakeguard.protection.ProtectionSettings
import com.shakeguard.ui.system.AccessibilityStatus
import com.shakeguard.ui.system.AccessibilityStatusReader
import com.shakeguard.ui.system.NotificationCapability
import com.shakeguard.ui.system.NotificationCapabilityReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeStatus { Loading, AccessibilityDisabled, ProtectionDisabled, NoSources, Active, Error }

data class HomeState(
    val status: HomeStatus = HomeStatus.Loading,
    val serviceConnected: Boolean = false,
    val notificationsAvailable: Boolean = true,
    val protectionEnabled: Boolean = false,
)

class HomeViewModel(
    sources: Flow<List<ManagedSource>>,
    gateStates: Flow<ProtectionGateState>,
    private val settings: ProtectionSettings,
    private val accessibilityStatusReader: AccessibilityStatusReader,
    private val notificationCapabilityReader: NotificationCapabilityReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val accessibility = MutableStateFlow<AccessibilityStatus?>(null)
    private val notifications = MutableStateFlow<NotificationCapability?>(null)
    private val systemReadFailed = MutableStateFlow(false)

    private val coreState = combine(sources, gateStates, settings.enabled) { sourceList, gate, protectionEnabled ->
        Triple(sourceList, gate, protectionEnabled)
    }

    val state: StateFlow<HomeState> = combine(
        coreState, accessibility, notifications, systemReadFailed,
    ) { core, accessibilityStatus, notification, readFailed ->
        val (sourceList, gate, protectionEnabled) = core
        val status = when {
            readFailed -> HomeStatus.Error
            accessibilityStatus == null || notification == null || gate == ProtectionGateState.NotReady -> HomeStatus.Loading
            accessibilityStatus != AccessibilityStatus.Enabled -> HomeStatus.AccessibilityDisabled
            gate == ProtectionGateState.Disabled -> HomeStatus.ProtectionDisabled
            sourceList.none { it.enabled } -> HomeStatus.NoSources
            else -> HomeStatus.Active
        }
        HomeState(
            status,
            accessibilityStatus == AccessibilityStatus.Enabled,
            notification == NotificationCapability.Available,
            protectionEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    init { refreshSystemStatus() }

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatcher) { settings.setEnabled(enabled) }
    }

    fun refreshSystemStatus() {
        viewModelScope.launch(dispatcher) {
            try {
                systemReadFailed.value = false
                accessibility.value = accessibilityStatusReader.read()
                notifications.value = notificationCapabilityReader.read()
            } catch (_: RuntimeException) {
                systemReadFailed.value = true
            }
        }
    }

    companion object {
        fun from(
            sources: Flow<List<ManagedSource>>,
            gate: ProtectionGate,
            settings: ProtectionSettings,
            accessibilityStatusReader: AccessibilityStatusReader,
            notificationCapabilityReader: NotificationCapabilityReader,
        ) = HomeViewModel(sources, gate.state, settings, accessibilityStatusReader, notificationCapabilityReader)
    }
}
