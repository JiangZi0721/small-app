package com.shakeguard.protection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionGateTest {
    @Test
    fun gateIsNotReadyBeforeFirstSettingValue() = runTest {
        val enabled = MutableStateFlow(true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gate = SettingsProtectionGate(FakeProtectionEnabledStore(enabled), scope)

        assertEquals(ProtectionGateState.NotReady, gate.state.value)
    }

    @Test
    fun gateTracksFalseAndTrueSettings() = runTest {
        val enabled = MutableSharedFlow<Boolean>(replay = 0)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gate = SettingsProtectionGate(
            object : ProtectionEnabledStore {
                override val enabled: Flow<Boolean> = enabled
            },
            scope,
        )

        assertEquals(ProtectionGateState.NotReady, gate.state.value)
        scope.testScheduler.runCurrent()
        enabled.emit(false)
        scope.testScheduler.runCurrent()
        assertEquals(ProtectionGateState.Disabled, gate.state.value)
        enabled.emit(true)
        scope.testScheduler.runCurrent()
        assertEquals(ProtectionGateState.Enabled, gate.state.value)
    }

    private class FakeProtectionEnabledStore(
        private val values: Flow<Boolean>,
    ) : ProtectionEnabledStore {
        override val enabled: Flow<Boolean> = values
    }
}
