package com.shakeguard.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.shakeguard.data.ManagedPairRule
import com.shakeguard.data.RuleRepository
import com.shakeguard.protection.RuleKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RuleFilter { All, Allow, Block, Conflict }
data class RuleListItem(val rule: ManagedPairRule, val hasConflict: Boolean) {
    val id: Long get() = rule.id
}
data class RulesUiState(val filter: RuleFilter = RuleFilter.All, val rules: List<RuleListItem> = emptyList(), val errorMessage: String? = null)

fun selectRuleForEditor(rules: List<ManagedPairRule>, ruleId: Long?): ManagedPairRule? =
    ruleId?.let { id -> rules.find { it.id == id } }

class RulesViewModelFactory(private val repository: RuleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = createViewModel(modelClass)
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = createViewModel(modelClass)
    private fun <T : ViewModel> createViewModel(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RulesViewModel::class.java))
        @Suppress("UNCHECKED_CAST") return RulesViewModel.from(repository) as T
    }
}

class RulesViewModel(
    rules: Flow<List<ManagedPairRule>>,
    private val now: () -> Long,
    private val setEnabled: suspend (Long, Boolean, Long) -> Boolean,
    private val delete: suspend (Long) -> Boolean,
    private val save: suspend (ManagedPairRule, Long) -> Long,
) : ViewModel() {
    private val filter = MutableStateFlow(RuleFilter.All)
    private val error = MutableStateFlow<String?>(null)
    val state: StateFlow<RulesUiState> = combine(rules, filter, error) { allRules, selected, message ->
        val kindsByPair = allRules.groupBy { it.sourcePackage to it.targetPackage }.mapValues { (_, values) -> values.map { it.kind }.toSet() }
        val items = allRules.map { rule -> RuleListItem(rule, kindsByPair[rule.sourcePackage to rule.targetPackage]?.containsAll(setOf(RuleKind.ALLOW, RuleKind.BLOCK)) == true) }
            .filter { item -> when (selected) { RuleFilter.All -> true; RuleFilter.Allow -> item.rule.kind == RuleKind.ALLOW; RuleFilter.Block -> item.rule.kind == RuleKind.BLOCK; RuleFilter.Conflict -> item.hasConflict } }
        RulesUiState(selected, items, message)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RulesUiState())

    fun setFilter(value: RuleFilter) { filter.value = value }
    fun setEnabled(id: Long, enabled: Boolean): Job = viewModelScope.launch {
        error.value = null
        try { if (!setEnabled(id, enabled, now())) error.value = "更新规则失败" }
        catch (exception: CancellationException) { throw exception }
        catch (_: RuntimeException) { error.value = "更新规则失败" }
    }
    fun deleteRule(id: Long): Job = viewModelScope.launch {
        error.value = null
        try { if (!delete(id)) error.value = "删除规则失败" }
        catch (exception: CancellationException) { throw exception }
        catch (_: RuntimeException) { error.value = "删除规则失败" }
    }
    fun saveRule(rule: ManagedPairRule): Job = viewModelScope.launch {
        error.value = null
        try { save(rule, now()) }
        catch (exception: CancellationException) { throw exception }
        catch (_: RuntimeException) { error.value = "保存规则失败" }
    }
    companion object {
        fun from(repository: RuleRepository) = RulesViewModel(repository.observeAllRules(), { System.currentTimeMillis() }, repository::setRuleEnabled, repository::deleteRule, repository::saveManagedRule)
    }
}
