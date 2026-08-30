package com.shakeguard.ui.activity

import com.shakeguard.data.ActivityEvent
import com.shakeguard.feedback.FeedbackCommand
import com.shakeguard.feedback.FeedbackResult
import com.shakeguard.protection.ActionResult
import com.shakeguard.protection.DecisionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogViewModelTest {
    @Test fun blockEventExposesAllFourFeedbackCommandsAndObserveExposesNone() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = ActivityLogViewModel(
                repository = FakeActivityRepository(listOf(
                    event(7L, DecisionKind.BLOCK), event(8L, DecisionKind.OBSERVE),
                )),
                handleFeedback = { FeedbackResult.Applied(it) },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            advanceUntilIdle()
            assertEquals(4, viewModel.state.value.events.first().feedbackActions.size)
            assertTrue(viewModel.state.value.events[1].feedbackActions.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun clearConfirmationDeletesOnlyEventsAndShowsEmptyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeActivityRepository(listOf(event(7L, DecisionKind.BLOCK)))
            val viewModel = ActivityLogViewModel(repository, { FeedbackResult.Applied(it) }, StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            viewModel.requestClear()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.clearConfirmationVisible)
            viewModel.confirmClear()
            advanceUntilIdle()
            assertEquals(1, repository.clearCalls)
            assertTrue(viewModel.state.value.isEmpty)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun feedbackNotFoundEmitsEventMissing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = ActivityLogViewModel(FakeActivityRepository(listOf(event(7L, DecisionKind.BLOCK))), { FeedbackResult.NotFound(7L) }, StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            viewModel.applyFeedback(FeedbackCommand.AllowPair(7L))
            advanceUntilIdle()
            assertEquals(ActivityLogEffect.EventMissing(7L), viewModel.effects.first())
        } finally { Dispatchers.resetMain() }
    }

    private fun event(id: Long, decision: DecisionKind) = ActivityEvent(id, "news", "store", 100L, decision, null, ActionResult.RETURNED, null, 10L)
    private class FakeActivityRepository(initial: List<ActivityEvent>) : ActivityEventRepository {
        private val values = MutableStateFlow(initial)
        var clearCalls = 0
        override fun observeRecentEvents(limit: Int) = values
        override suspend fun clearAllEvents(): Int { clearCalls++; val count = values.value.size; values.value = emptyList(); return count }
    }
}
