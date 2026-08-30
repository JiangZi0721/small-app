package com.shakeguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Test

class UiDependencyContractTest {
    @Test
    fun lifecycleAndCoroutineTestDependenciesAreAvailable() {
        val viewModel = object : ViewModel() {}

        assertNotNull(viewModel.viewModelScope)
        assertNotNull(StandardTestDispatcher())
    }
}
