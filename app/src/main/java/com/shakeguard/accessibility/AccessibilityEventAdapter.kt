package com.shakeguard.accessibility

import android.view.accessibility.AccessibilityEvent

data class ForegroundWindowEvent(
    val packageName: String,
    val className: String?,
)

class AccessibilityEventAdapter {
    fun adapt(event: AccessibilityEvent?): ForegroundWindowEvent? {
        if (event == null || event.eventType !in SUPPORTED_EVENT_TYPES) return null
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return ForegroundWindowEvent(packageName, event.className?.toString())
    }

    private companion object {
        val SUPPORTED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}
