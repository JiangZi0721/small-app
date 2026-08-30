package com.shakeguard.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import com.shakeguard.feedback.FeedbackCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackNotificationContractTest {
    @Test
    fun exactActionsMapToFeedbackCommands() {
        assertEquals(
            FeedbackCommand.AllowOnce(42L),
            FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_ALLOW_ONCE, 42L),
        )
        assertEquals(
            FeedbackCommand.AllowPair(42L),
            FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_ALLOW_PAIR, 42L),
        )
        assertEquals(
            FeedbackCommand.ConfirmAd(42L),
            FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_CONFIRM_AD, 42L),
        )
        assertEquals(
            FeedbackCommand.StopProtectingSource(42L),
            FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_STOP_PROTECTING_SOURCE, 42L),
        )
    }

    @Test
    fun malformedCommandInputsAreRejected() {
        assertNull(FeedbackReceiver.commandFor(null, 42L))
        assertNull(FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_ALLOW_ONCE, null))
        assertNull(FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_ALLOW_ONCE, 0L))
        assertNull(FeedbackReceiver.commandFor(FeedbackReceiver.ACTION_ALLOW_ONCE, -1L))
        assertNull(FeedbackReceiver.commandFor("com.shakeguard.action.UNKNOWN", 42L))
    }

    @Test
    fun requestCodesAreStableAndDistinctPerEventActionPair() {
        val firstEvent = (0..3).map { ordinal ->
            ProtectionNotificationManager.requestCode(42L, ordinal)
        }
        val secondEvent = (0..3).map { ordinal ->
            ProtectionNotificationManager.requestCode(43L, ordinal)
        }

        assertEquals(firstEvent, (0..3).map { ordinal ->
            ProtectionNotificationManager.requestCode(42L, ordinal)
        })
        assertEquals(4, firstEvent.toSet().size)
        assertEquals(4, secondEvent.toSet().size)
        assertTrue(firstEvent.zip(secondEvent).all { (first, second) -> first != second })
        assertNotEquals(firstEvent.toSet(), secondEvent.toSet())
    }

    @Test
    fun runtimePermissionFailureReturnsFalseInsteadOfEscaping() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val notificationManager = NotificationManager::class.java
            .getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()

        val manager = ProtectionNotificationManager(
            context = context,
            notificationManager = notificationManager,
            permissionChecker = { throw IllegalStateException("permission service unavailable") },
        )

        assertFalse(manager.publish(42L, "source", "target"))
    }
}
