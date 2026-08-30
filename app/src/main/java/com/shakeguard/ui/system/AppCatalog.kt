package com.shakeguard.ui.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val icon: Drawable?)

internal data class CatalogCandidate(
    val packageName: String,
    val label: String?,
    val icon: Drawable?,
    val isSystemApp: Boolean,
)

fun interface AppCatalog {
    suspend fun loadLaunchableApps(): List<InstalledApp>
}

internal fun resolveAppLabel(packageName: String, resolver: () -> String?): String =
    runCatching { resolver()?.takeIf { it.isNotBlank() } }.getOrNull() ?: packageName

internal fun selectUserInstalledApps(
    candidates: List<CatalogCandidate>,
    ownPackageName: String,
): List<InstalledApp> = candidates
    .asSequence()
    .filter { it.packageName != ownPackageName && !it.isSystemApp }
    .map { candidate ->
        InstalledApp(
            packageName = candidate.packageName,
            label = resolveAppLabel(candidate.packageName) { candidate.label },
            icon = candidate.icon,
        )
    }
    .distinctBy { it.packageName }
    .sortedWith(
        Comparator { left, right ->
            val byLabel = left.label.compareTo(right.label, ignoreCase = true)
            if (byLabel != 0) byLabel else left.packageName.compareTo(right.packageName, ignoreCase = true)
        },
    )
    .toList()

class AndroidAppCatalog(private val context: Context) : AppCatalog {
    override suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val candidates = packageManager
            .getInstalledApplications(0)
            .map { info ->
                CatalogCandidate(
                    packageName = info.packageName,
                    label = resolveAppLabel(info.packageName) { info.loadLabel(packageManager)?.toString() },
                    icon = runCatching { info.loadIcon(packageManager) }.getOrNull(),
                    isSystemApp = info.flags and systemFlags != 0,
                )
            }
        selectUserInstalledApps(candidates, context.packageName)
    }
}
