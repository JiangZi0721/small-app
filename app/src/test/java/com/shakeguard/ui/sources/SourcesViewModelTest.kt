package com.shakeguard.ui.sources

import com.shakeguard.data.ManagedSource
import com.shakeguard.protection.ProtectionSettings
import com.shakeguard.ui.system.AppCatalog
import com.shakeguard.ui.system.InstalledApp
import com.shakeguard.ui.system.CatalogCandidate
import com.shakeguard.ui.system.selectUserInstalledApps
import com.shakeguard.ui.system.resolveAppLabel
import com.shakeguard.ui.navigation.sourcePickerContext
import android.content.ContextWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {
    @Test
    fun userInstalledCatalogKeepsNonLauncherAppsAndExcludesSystemAndSelf() {
        val apps = selectUserInstalledApps(
            candidates = listOf(
                CatalogCandidate("com.sankuai.meituan", "Meituan", null, isSystemApp = false),
                CatalogCandidate("com.baidu.netdisk", "", null, isSystemApp = false),
                CatalogCandidate("com.android.systemui", "System UI", null, isSystemApp = true),
                CatalogCandidate("com.shakeguard", "ShakeGuard", null, isSystemApp = false),
            ),
            ownPackageName = "com.shakeguard",
        )

        assertEquals(listOf("com.baidu.netdisk", "com.sankuai.meituan"), apps.map { it.packageName })
        assertEquals("com.baidu.netdisk", apps.first().label)
    }

    @Test
    fun missingSourceCreatesDraftFromSettingsWithoutPersisting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var saves = 0
            val viewModel = SourceDetailViewModel(
                packageName = "news.app",
                catalog = FakeCatalog(listOf(InstalledApp("news.app", "Daily News", null))),
                sources = MutableStateFlow(emptyList()),
                relatedRuleCount = flowOf(0),
                settings = FakeSettings(initialWindowMs = 7_000L),
                now = { 10L },
                saveManagedSource = { _, _ -> saves++ },
                setSourceEnabled = { _, _, _ -> true },
            )

            advanceUntilIdle()

            assertEquals("news.app", viewModel.state.value.packageName)
            assertEquals("Daily News", viewModel.state.value.label)
            assertEquals(7_000L, viewModel.state.value.windowMs)
            assertTrue(viewModel.state.value.sourceLevelBlock)
            assertEquals(0, saves)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun saveDelegatesManagedSourceWithMillisecondWindow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var saved: ManagedSource? = null
            var updatedAt: Long? = null
            val viewModel = SourceDetailViewModel(
                packageName = "news.app",
                catalog = FakeCatalog(emptyList()),
                sources = MutableStateFlow(emptyList()),
                relatedRuleCount = flowOf(2),
                settings = FakeSettings(initialWindowMs = 5_000L),
                now = { 99L },
                saveManagedSource = { source, timestamp -> saved = source; updatedAt = timestamp },
                setSourceEnabled = { _, _, _ -> true },
            )
            advanceUntilIdle()

            viewModel.setWindowSeconds("12")
            advanceUntilIdle()
            viewModel.save()
            advanceUntilIdle()

            assertEquals(12_000L, saved?.windowMs)
            assertTrue(saved?.sourceLevelBlock == true)
            assertEquals(99L, updatedAt)
            assertEquals(2, viewModel.state.value.relatedRuleCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resumeCallsExactRepositoryMutationWithoutChangingExistingDetails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var mutation: Triple<String, Boolean, Long>? = null
            val sources = MutableStateFlow(
                listOf(ManagedSource("news.app", false, 9_000L, false, 1L, 2L)),
            )
            val viewModel = SourceDetailViewModel(
                packageName = "news.app",
                catalog = FakeCatalog(emptyList()),
                sources = sources,
                relatedRuleCount = flowOf(3),
                settings = FakeSettings(initialWindowMs = 5_000L),
                now = { 100L },
                saveManagedSource = { _, _ -> error("save should not be used") },
                setSourceEnabled = { packageName, enabled, timestamp ->
                    mutation = Triple(packageName, enabled, timestamp)
                    true
                },
            )
            advanceUntilIdle()

            viewModel.setEnabled(true)
            advanceUntilIdle()

            assertEquals(Triple("news.app", true, 100L), mutation)
            assertEquals(9_000L, viewModel.state.value.windowMs)
            assertTrue(!viewModel.state.value.sourceLevelBlock)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun savePropagatesCancellationWithoutSettingAnError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = SourceDetailViewModel(
                packageName = "news.app",
                catalog = FakeCatalog(emptyList()),
                sources = MutableStateFlow(emptyList()),
                relatedRuleCount = flowOf(0),
                settings = FakeSettings(initialWindowMs = 5_000L),
                now = { 1L },
                saveManagedSource = { _, _ -> throw CancellationException("cancel save") },
                setSourceEnabled = { _, _, _ -> true },
            )
            advanceUntilIdle()

            val job = viewModel.save()
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertEquals(null, viewModel.state.value.errorMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun enableChangePropagatesCancellationWithoutSettingAnError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = SourceDetailViewModel(
                packageName = "news.app",
                catalog = FakeCatalog(emptyList()),
                sources = MutableStateFlow(listOf(ManagedSource("news.app", false, 5_000L, true, 1L, 2L))),
                relatedRuleCount = flowOf(0),
                settings = FakeSettings(initialWindowMs = 5_000L),
                now = { 1L },
                saveManagedSource = { _, _ -> Unit },
                setSourceEnabled = { _, _, _ -> throw CancellationException("cancel enable") },
            )
            advanceUntilIdle()

            val job = viewModel.setEnabled(true)
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertEquals(null, viewModel.state.value.errorMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }
    @Test
    fun sourcePickerUsesApplicationContextForLongLivedCatalog() {
        val application = ContextWrapper(null)
        val activity = object : ContextWrapper(null) {
            override fun getApplicationContext() = application
        }

        assertEquals(application, sourcePickerContext(activity))
    }
    @Test
    fun factoryCreatesLifecycleManagedPickerViewModel() {
        val created = SourcesViewModelFactory(FakeCatalog(emptyList()), MutableStateFlow(emptyList()))
            .create(SourcesViewModel::class.java)

        assertNotNull(created)
    }
    @Test
    fun labelResolutionFallsBackForBlankAndRuntimeFailure() {
        assertEquals("news.app", resolveAppLabel("news.app") { "" })
        assertEquals("video.app", resolveAppLabel("video.app") { error("broken label") })
    }
    @Test
    fun searchesLabelsAndPackagesExcludesShakeGuardAndMarksPausedSources() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
        val viewModel = SourcesViewModel(
            catalog = FakeCatalog(
                listOf(
                    InstalledApp("com.shakeguard", "ShakeGuard", null),
                    InstalledApp("news.app", "Daily News", null),
                    InstalledApp("video.app", "Video", null),
                ),
            ),
            sources = MutableStateFlow(listOf(ManagedSource("news.app", false, 5_000L, true, 1L, 2L))),
        )

        advanceUntilIdle()
        viewModel.setQuery("news")
        advanceUntilIdle()

        assertEquals(listOf("news.app"), viewModel.state.value.visibleApps.map { it.packageName })
        assertEquals(SourcePickerStatus.Paused, viewModel.state.value.visibleApps.single().status)
        assertTrue(viewModel.state.value.visibleApps.none { it.packageName == "com.shakeguard" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeCatalog(private val apps: List<InstalledApp>) : AppCatalog {
        override suspend fun loadLaunchableApps(): List<InstalledApp> = apps
    }

    private class FakeSettings(private val initialWindowMs: Long) : ProtectionSettings {
        override val enabled = flowOf(true)
        override val windowMs = flowOf(initialWindowMs)
        override val notifications = flowOf(false)
        override suspend fun setEnabled(value: Boolean) = Unit
        override suspend fun setWindowMs(value: Long) = Unit
        override suspend fun setNotifications(value: Boolean) = Unit
    }
}
