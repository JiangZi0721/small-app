package com.shakeguard.ui.rules

import com.shakeguard.data.ManagedPairRule
import com.shakeguard.protection.RuleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {
    @Test
    fun editorSelectionKeepsExistingRuleInsteadOfCreatingBlankDraft() {
        val rule = ManagedPairRule(7L, "news", "store", RuleKind.BLOCK, true, "UI", 8L)
        assertEquals(rule, selectRuleForEditor(listOf(rule), 7L))
        assertEquals(null, selectRuleForEditor(listOf(rule), null))
    }
    @Test
    fun conflictFilterKeepsBothOppositeKindsEvenWhenDisabled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = RulesViewModel(
                rules = MutableStateFlow(listOf(
                    ManagedPairRule(1L, "news", "store", RuleKind.ALLOW, false, "UI", 1L),
                    ManagedPairRule(2L, "news", "store", RuleKind.BLOCK, true, "FEEDBACK", 2L),
                    ManagedPairRule(3L, "video", "store", RuleKind.BLOCK, true, "UI", 3L),
                )),
                now = { 10L },
                setEnabled = { _, _, _ -> true },
                delete = { true },
                save = { _, _ -> 1L },
            )
            advanceUntilIdle()

            viewModel.setFilter(RuleFilter.Conflict)
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), viewModel.state.value.rules.map { it.id })
            assertTrue(viewModel.state.value.rules.all { it.hasConflict })
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun disablingRuleMutatesOnlySelectedId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var mutation: Triple<Long, Boolean, Long>? = null
            val viewModel = RulesViewModel(
                rules = MutableStateFlow(emptyList()), now = { 55L },
                setEnabled = { id, enabled, time -> mutation = Triple(id, enabled, time); true },
                delete = { true }, save = { _, _ -> 1L },
            )
            advanceUntilIdle()

            viewModel.setEnabled(7L, false)
            advanceUntilIdle()

            assertEquals(Triple(7L, false, 55L), mutation)
        } finally { Dispatchers.resetMain() }
    }
}
