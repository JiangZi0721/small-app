package com.shakeguard.ui.home

import com.shakeguard.data.ManagedSource
import com.shakeguard.protection.ProtectionGateState
import com.shakeguard.protection.ProtectionSettings
import com.shakeguard.ui.system.AccessibilityStatus
import com.shakeguard.ui.system.AccessibilityStatusReader
import com.shakeguard.ui.system.NotificationCapability
import com.shakeguard.ui.system.NotificationCapabilityReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test
    fun accessibilityDisabledTakesPriorityOverProtectionAndSources() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        try {
        val viewModel = HomeViewModel(
            sources = MutableStateFlow(emptyList()),
            gateStates = MutableStateFlow(ProtectionGateState.Disabled),
            settings = FakeSettings(),
            accessibilityStatusReader = FakeAccessibilityReader(AccessibilityStatus.Disabled),
            notificationCapabilityReader = FakeNotificationReader(NotificationCapability.Available),
            dispatcher = dispatcher,
        )

        testScheduler.advanceUntilIdle()

        assertEquals(HomeStatus.AccessibilityDisabled, viewModel.state.value.status)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun readerErrorProducesErrorStatus() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        try {
            val viewModel = HomeViewModel(
                sources = MutableStateFlow(listOf(source())),
                gateStates = MutableStateFlow(ProtectionGateState.Enabled),
                settings = FakeSettings(),
                accessibilityStatusReader = ThrowingAccessibilityReader(),
                notificationCapabilityReader = FakeNotificationReader(NotificationCapability.Available),
                dispatcher = dispatcher,
            )
            testScheduler.advanceUntilIdle()
            assertEquals(HomeStatus.Error, viewModel.state.value.status)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun setProtectionEnabledWritesSharedSettingsStore() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        try {
            val settings = FakeSettings()
            val viewModel = HomeViewModel(
                MutableStateFlow(listOf(source())), MutableStateFlow(ProtectionGateState.Enabled), settings,
                FakeAccessibilityReader(AccessibilityStatus.Enabled), FakeNotificationReader(NotificationCapability.Available), dispatcher,
            )
            viewModel.setProtectionEnabled(false)
            testScheduler.advanceUntilIdle()
            assertEquals(false, settings.enabled.value)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    private fun source() = ManagedSource("source", true, 5_000L, false, 1L, 1L)

    private class FakeSettings : ProtectionSettings {
        override val enabled = MutableStateFlow(true)
        override val windowMs = MutableStateFlow(5_000L)
        override val notifications = MutableStateFlow(false)
        override suspend fun setEnabled(value: Boolean) { enabled.value = value }
        override suspend fun setWindowMs(value: Long) = Unit
        override suspend fun setNotifications(value: Boolean) = Unit
    }

    private class FakeAccessibilityReader(private val value: AccessibilityStatus) : AccessibilityStatusReader {
        override suspend fun read() = value
    }

    private class FakeNotificationReader(private val value: NotificationCapability) : NotificationCapabilityReader {
        override suspend fun read() = value
    }

    private class ThrowingAccessibilityReader : AccessibilityStatusReader {
        override suspend fun read(): AccessibilityStatus = error("system read failed")
    }
}
