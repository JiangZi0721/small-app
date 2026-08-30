package com.shakeguard.ui.navigation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.shakeguard.ShakeGuardApplication
import com.shakeguard.ui.home.HomeScreen
import com.shakeguard.ui.home.HomeViewModel
import com.shakeguard.ui.rules.RulesScreen
import com.shakeguard.ui.rules.RulesViewModel
import com.shakeguard.ui.rules.RulesViewModelFactory
import com.shakeguard.ui.rules.selectRuleForEditor
import com.shakeguard.ui.activity.ActivityLogScreen
import com.shakeguard.ui.activity.ActivityLogViewModel
import com.shakeguard.ui.rules.RuleEditorScreen
import com.shakeguard.ui.sources.SourcePickerScreen
import com.shakeguard.ui.sources.SourceDetailScreen
import com.shakeguard.ui.sources.SourceDetailViewModel
import com.shakeguard.ui.sources.SourcesViewModel
import com.shakeguard.ui.sources.SourcesViewModelFactory
import com.shakeguard.ui.system.AndroidAccessibilityStatusReader
import com.shakeguard.ui.system.AndroidAppCatalog
import com.shakeguard.ui.system.AndroidNotificationCapabilityReader

internal fun sourcePickerContext(context: Context): Context = context.applicationContext

@Composable
fun AppNavHost(navController: NavHostController, contentPadding: PaddingValues) {
    NavHost(navController, AppRoutes.HOME, Modifier.padding(contentPadding)) {
        composable(AppRoutes.HOME) { HomeDestination(navController) }
        composable(AppRoutes.RULES) { RulesDestination(navController) }
        composable(AppRoutes.ACTIVITY) { ActivityDestination() }
        composable(AppRoutes.APP_PICKER) { SourcePickerDestination(navController) }
        composable(AppRoutes.SOURCE) { backStackEntry ->
            val packageName = requireNotNull(backStackEntry.arguments?.getString("packageName"))
            SourceDetailDestination(navController, packageName)
        }
        composable(AppRoutes.RULE_EDITOR) { entry ->
            val ruleId = entry.arguments?.getString("ruleId")?.toLongOrNull()
            RuleEditorDestination(navController, ruleId)
        }
    }
}

@Composable private fun ActivityDestination() {
    val application = LocalContext.current.applicationContext as ShakeGuardApplication
    val viewModel: ActivityLogViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return ActivityLogViewModel.from(application.ruleRepository, application.feedbackHandler) as T }
    })
    val state by viewModel.state.collectAsState()
    ActivityLogScreen(state, viewModel::requestClear, viewModel::confirmClear, viewModel::dismissClear, { viewModel.applyFeedback(it) })
}

@Composable
private fun RulesDestination(navController: NavHostController) {
    val application = LocalContext.current.applicationContext as ShakeGuardApplication
    val viewModel: RulesViewModel = viewModel(factory = RulesViewModelFactory(application.ruleRepository))
    val state by viewModel.state.collectAsState()
    RulesScreen(state, viewModel::setFilter, viewModel::setEnabled, viewModel::deleteRule) { id -> navController.navigate(AppRoutes.ruleEditor(id)) }
}

@Composable
private fun RuleEditorDestination(navController: NavHostController, ruleId: Long?) {
    val application = LocalContext.current.applicationContext as ShakeGuardApplication
    val viewModel: RulesViewModel = viewModel(key = "rule-editor-$ruleId", factory = RulesViewModelFactory(application.ruleRepository))
    val state by viewModel.state.collectAsState()
    val rule = selectRuleForEditor(state.rules.map { it.rule }, ruleId)
    RuleEditorScreen(rule, viewModel::saveRule) { navController.popBackStack() }
}

@Composable
private fun HomeDestination(navController: NavHostController) {
    val context = LocalContext.current
    val application = context.applicationContext as ShakeGuardApplication
    val viewModel = remember {
        HomeViewModel.from(application.ruleRepository.observeAllSources(), application.protectionGate, application.settingsStore,
            AndroidAccessibilityStatusReader(context), AndroidNotificationCapabilityReader(context))
    }
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemStatus() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    HomeScreen(state, viewModel::setProtectionEnabled, { navController.navigate(AppRoutes.APP_PICKER) },
        { launchSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        { launchSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) })
}

@Composable
private fun SourcePickerDestination(navController: NavHostController) {
    val context = LocalContext.current
    val application = context.applicationContext as ShakeGuardApplication
    val viewModel: SourcesViewModel = viewModel(
        factory = SourcesViewModelFactory(AndroidAppCatalog(sourcePickerContext(context)), application.ruleRepository.observeAllSources()),
    )
    val state by viewModel.state.collectAsState()
    SourcePickerScreen(state, viewModel::setQuery) { packageName -> navController.navigate(AppRoutes.source(packageName)) }
}

@Composable
private fun SourceDetailDestination(navController: NavHostController, packageName: String) {
    val context = LocalContext.current
    val application = context.applicationContext as ShakeGuardApplication
    val viewModel: SourceDetailViewModel = viewModel(
        key = packageName,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SourceDetailViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return SourceDetailViewModel.from(
                    packageName,
                    AndroidAppCatalog(context.applicationContext),
                    application.ruleRepository,
                    application.settingsStore,
                ) as T
            }
        },
    )
    val state by viewModel.state.collectAsState()
    SourceDetailScreen(
        state = state,
        onEnabledChanged = viewModel::setEnabled,
        onWindowSecondsChanged = viewModel::setWindowSeconds,
        onSourceLevelBlockChanged = viewModel::setSourceLevelBlock,
        onSave = viewModel::save,
        onShowRelatedRules = { navController.navigate(AppRoutes.RULES) },
    )
}


private fun launchSettings(context: Context, intent: Intent) {
    try { context.startActivity(intent) } catch (_: RuntimeException) { }
}
