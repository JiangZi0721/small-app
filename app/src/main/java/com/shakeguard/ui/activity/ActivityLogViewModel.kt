package com.shakeguard.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakeguard.data.ActivityEvent
import com.shakeguard.data.RuleRepository
import com.shakeguard.feedback.FeedbackCommand
import com.shakeguard.feedback.FeedbackHandler
import com.shakeguard.feedback.FeedbackResult
import com.shakeguard.protection.DecisionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface ActivityEventRepository {
    fun observeRecentEvents(limit: Int = 100): Flow<List<ActivityEvent>>
    suspend fun clearAllEvents(): Int
}

data class ActivityLogUiState(
    val events: List<ActivityEventUiModel> = emptyList(), val loading: Boolean = true,
    val isEmpty: Boolean = false, val clearConfirmationVisible: Boolean = false,
    val clearing: Boolean = false, val errorMessage: String? = null,
)

class ActivityLogViewModel(
    repository: ActivityEventRepository,
    private val handleFeedback: suspend (FeedbackCommand) -> FeedbackResult,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val events = MutableStateFlow<List<ActivityEventUiModel>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val clearConfirmation = MutableStateFlow(false)
    private val clearing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val effectChannel = Channel<ActivityLogEffect>(Channel.BUFFERED)
    private lateinit var clearAction: suspend () -> Int
    val effects = effectChannel.receiveAsFlow()
    val state: StateFlow<ActivityLogUiState> = combine(events, loading, clearConfirmation, clearing, error) { rows, isLoading, confirm, isClearing, message ->
        ActivityLogUiState(rows, isLoading, !isLoading && rows.isEmpty(), confirm, isClearing, message)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActivityLogUiState())

    init { viewModelScope.launch { repository.observeRecentEvents(100).collect { events.value = it.map(::toUi); loading.value = false } } ; clearAction = { repository.clearAllEvents() } }
    fun requestClear() { clearConfirmation.value = true }
    fun dismissClear() { clearConfirmation.value = false }
    fun confirmClear(): Job = viewModelScope.launch(dispatcher) {
        clearing.value = true; error.value = null
        try { clearAction(); events.value = emptyList(); clearConfirmation.value = false }
        catch (exception: CancellationException) { throw exception }
        catch (exception: RuntimeException) { error.value = "清空活动记录失败"; effectChannel.send(ActivityLogEffect.Failure(0L, exception)) }
        finally { clearing.value = false }
    }
    fun applyFeedback(command: FeedbackCommand): Job = viewModelScope.launch(dispatcher) {
        try {
            when (val result = handleFeedback(command)) {
                is FeedbackResult.Applied -> effectChannel.send(ActivityLogEffect.Message("反馈已应用"))
                is FeedbackResult.NotFound -> effectChannel.send(ActivityLogEffect.EventMissing(result.eventId))
                is FeedbackResult.Failed -> effectChannel.send(ActivityLogEffect.Failure(result.eventId, result.cause))
            }
        } catch (exception: CancellationException) { throw exception }
        catch (exception: RuntimeException) { effectChannel.send(ActivityLogEffect.Failure(command.eventId, exception)) }
    }

    private fun toUi(event: ActivityEvent): ActivityEventUiModel {
        val actions = if (event.decision == DecisionKind.BLOCK && event.id > 0L) listOf(
            FeedbackCommand.AllowOnce(event.id), FeedbackCommand.AllowPair(event.id),
            FeedbackCommand.ConfirmAd(event.id), FeedbackCommand.StopProtectingSource(event.id),
        ) else emptyList()
        return ActivityEventUiModel(event.id, event.sourcePackage, event.targetPackage, event.elapsedMs, event.decision.name, event.matchedRuleId, event.actionResult.name, event.userFeedback, event.createdAt, actions)
    }
    companion object { fun from(repository: RuleRepository, handler: FeedbackHandler) = ActivityLogViewModel(object : ActivityEventRepository { override fun observeRecentEvents(limit: Int) = repository.observeRecentEvents(limit); override suspend fun clearAllEvents() = repository.clearAllEvents() }, handler::handle) }
}
