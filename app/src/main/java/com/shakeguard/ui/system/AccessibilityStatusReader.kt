package com.shakeguard.ui.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.shakeguard.accessibility.ShakeGuardAccessibilityService

enum class AccessibilityStatus { Enabled, Disabled, Unavailable }

fun interface AccessibilityStatusReader {
    suspend fun read(): AccessibilityStatus
}

class AndroidAccessibilityStatusReader(private val context: Context) : AccessibilityStatusReader {
    override suspend fun read(): AccessibilityStatus = try {
        val manager = context.getSystemService(AccessibilityManager::class.java)
            ?: return AccessibilityStatus.Unavailable
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == ShakeGuardAccessibilityService::class.java.name }
        if (enabled) AccessibilityStatus.Enabled else AccessibilityStatus.Disabled
    } catch (_: RuntimeException) {
        AccessibilityStatus.Unavailable
    }
}
