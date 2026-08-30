package com.shakeguard.accessibility

import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityEventAdapterInstrumentedTest {
    private val adapter = AccessibilityEventAdapter()

    @Test
    fun windowStateEventUsesOnlyPackageAndClassName() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.example.news"
            className = "com.example.news.SplashActivity"
            text += "content that must not be read"
        }

        assertEquals(
            ForegroundWindowEvent("com.example.news", "com.example.news.SplashActivity"),
            adapter.adapt(event),
        )
        event.recycle()
    }

    @Test
    fun windowsChangedEventIsSupported() {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED).apply {
            packageName = "com.example.store"
        }

        assertEquals("com.example.store", adapter.adapt(event)?.packageName)
        event.recycle()
    }

    @Test
    fun unsupportedOrMissingPackageEventIsIgnored() {
        val click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.example.news"
        }
        val missingPackage = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

        assertNull(adapter.adapt(click))
        assertNull(adapter.adapt(missingPackage))
        click.recycle()
        missingPackage.recycle()
    }
}
