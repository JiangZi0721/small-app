package com.shakeguard.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.shakeguard.data.ManagedSource
import com.shakeguard.data.RuleRepository
import com.shakeguard.protection.ProtectionSettings
import com.shakeguard.ui.system.AppCatalog
import com.shakeguard.ui.system.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SourcePickerStatus { Unconfigured, Protected, Paused }
data class SourcePickerItem(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable?, val status: SourcePickerStatus)
data class SourcePickerUiState(val query: String = "", val loading: Boolean = true, val visibleApps: List<SourcePickerItem> = emptyList())

class SourcesViewModel(
    private val catalog: AppCatalog,
    sources: Flow<List<ManagedSource>>,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val loading = MutableStateFlow(true)

    val state: StateFlow<SourcePickerUiState> = combine(query, apps, sources, loading) { text, installed, configured, isLoading ->
        val configuredByPackage = configured.associateBy { it.packageName }
        val normalized = text.trim().lowercase()
        val items = installed.map { app ->
            val source = configuredByPackage[app.packageName]
            SourcePickerItem(
                app.packageName, app.label, app.icon,
                when { source == null -> SourcePickerStatus.Unconfigured; source.enabled -> SourcePickerStatus.Protected; else -> SourcePickerStatus.Paused },
            )
        }.filter { normalized.isEmpty() || it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized) }
        SourcePickerUiState(text, isLoading, items)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SourcePickerUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            apps.value = try {
                catalog.loadLaunchableApps()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                emptyList()
            }
            loading.value = false
        }
    }

    fun setQuery(value: String) { query.value = value }
}

class SourcesViewModelFactory(
    private val catalog: AppCatalog,
    private val sources: Flow<List<ManagedSource>>,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = createViewModel(modelClass)

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return createViewModel(modelClass)
    }

    private fun <T : ViewModel> createViewModel(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SourcesViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return SourcesViewModel(catalog, sources) as T
    }
}

data class SourceDetailUiState(
    val packageName: String,
    val label: String = packageName,
    val icon: android.graphics.drawable.Drawable? = null,
    val enabled: Boolean = true,
    val windowMs: Long = 5_000L,
    val sourceLevelBlock: Boolean = true,
    val relatedRuleCount: Int = 0,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

private data class SourceDraft(val enabled: Boolean, val windowMs: Long, val sourceLevelBlock: Boolean, val createdAt: Long)
private data class SourceDetailInputs(
    val source: ManagedSource?,
    val ruleCount: Int,
    val installedApp: InstalledApp?,
    val defaultWindow: Long,
)

class SourceDetailViewModel(
    private val packageName: String,
    private val catalog: AppCatalog,
    sources: Flow<List<ManagedSource>>,
    relatedRuleCount: Flow<Int>,
    private val settings: ProtectionSettings,
    private val now: () -> Long,
    private val saveManagedSource: suspend (ManagedSource, Long) -> Unit,
    private val setSourceEnabled: suspend (String, Boolean, Long) -> Boolean,
) : ViewModel() {
    private val configured = MutableStateFlow<ManagedSource?>(null)
    private val app = MutableStateFlow<InstalledApp?>(null)
    private val defaultWindowMs = MutableStateFlow(5_000L)
    private val draft = MutableStateFlow<SourceDraft?>(null)
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val detailInputs = combine(configured, relatedRuleCount, app, defaultWindowMs) { source, ruleCount, installedApp, defaultWindow ->
        SourceDetailInputs(source, ruleCount, installedApp, defaultWindow)
    }

    val state: StateFlow<SourceDetailUiState> = combine(detailInputs, draft, saving, error) { inputs, currentDraft, isSaving, message ->
        val effectiveDraft = currentDraft ?: inputs.source?.let {
            SourceDraft(it.enabled, it.windowMs, it.sourceLevelBlock, it.createdAt)
        } ?: SourceDraft(true, inputs.defaultWindow, true, now())
        SourceDetailUiState(
            packageName = packageName,
            label = inputs.installedApp?.label ?: packageName,
            icon = inputs.installedApp?.icon,
            enabled = effectiveDraft.enabled,
            windowMs = effectiveDraft.windowMs,
            sourceLevelBlock = effectiveDraft.sourceLevelBlock,
            relatedRuleCount = inputs.ruleCount,
            saving = isSaving,
            errorMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SourceDetailUiState(packageName))

    init {
        viewModelScope.launch { sources.collect { sourceList -> configured.value = sourceList.find { it.packageName == packageName } } }
        viewModelScope.launch(Dispatchers.IO) {
            app.value = try {
                catalog.loadLaunchableApps().find { it.packageName == packageName }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                null
            }
            defaultWindowMs.value = settings.windowMs.first()
        }
    }

    fun setWindowSeconds(value: String) {
        val seconds = value.toLongOrNull()?.coerceIn(1L, 30L) ?: return
        updateDraft { it.copy(windowMs = seconds * 1_000L) }
    }

    fun setSourceLevelBlock(value: Boolean) = updateDraft { it.copy(sourceLevelBlock = value) }

    fun setEnabled(value: Boolean): Job =
        viewModelScope.launch {
            val current = state.value
            saving.value = true
            error.value = null
            try {
                if (!setSourceEnabled(packageName, value, now())) error.value = "更新保护状态失败"
                else draft.value = SourceDraft(value, current.windowMs, current.sourceLevelBlock, configured.value?.createdAt ?: now())
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                error.value = "更新保护状态失败"
            } finally { saving.value = false }
        }

    fun save(): Job =
        viewModelScope.launch {
            val current = state.value
            saving.value = true
            error.value = null
            try {
                val timestamp = now()
                saveManagedSource(
                    ManagedSource(packageName, current.enabled, current.windowMs, current.sourceLevelBlock, configured.value?.createdAt ?: timestamp, timestamp),
                    timestamp,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                error.value = "保存来源失败"
            } finally { saving.value = false }
        }

    private fun updateDraft(transform: (SourceDraft) -> SourceDraft) {
        val current = state.value
        draft.value = transform(SourceDraft(current.enabled, current.windowMs, current.sourceLevelBlock, configured.value?.createdAt ?: now()))
    }

    companion object {
        fun from(packageName: String, catalog: AppCatalog, repository: RuleRepository, settings: ProtectionSettings): SourceDetailViewModel =
            SourceDetailViewModel(
                packageName, catalog, repository.observeAllSources(), repository.countRulesForSource(packageName), settings,
                now = { System.currentTimeMillis() },
                saveManagedSource = repository::saveManagedSource,
                setSourceEnabled = repository::setSourceEnabled,
            )
    }
}
