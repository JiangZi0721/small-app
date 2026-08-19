package com.shakeguard.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shakeguard.ShakeGuardApplication
import com.shakeguard.data.JumpEventEntity
import com.shakeguard.data.ProtectedSourceEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectionNotificationInstrumentedTest {
    private lateinit var application: ShakeGuardApplication
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(FeedbackReceiver.CHANNEL_ID)
        }
        runBlocking {
            withContext(Dispatchers.IO) {
                application.database.clearAllTables()
            }
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(FeedbackReceiver.CHANNEL_ID)
        }
        runBlocking {
            withContext(Dispatchers.IO) {
                application.database.clearAllTables()
            }
        }
    }

    @Test
    fun ensureChannelCreatesProtectionFeedbackChannelAndNotificationHasFourImmutableActions() {
        val manager = ProtectionNotificationManager(application, notificationManager)

        assertTrue(manager.ensureChannel())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(FeedbackReceiver.CHANNEL_ID)
            assertNotNull(channel)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
        }

        val eventId = 42_001L
        val notification = manager.buildNotification(
            eventId = eventId,
            sourcePackage = application.packageName,
            targetPackage = application.packageName,
        )

        assertEquals(4, notification.actions.size)
        val label = application.applicationInfo.loadLabel(application.packageManager).toString()
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                .contains("ShakeGuard"),
        )
        assertEquals(
            "$label to $label",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        notification.actions.forEachIndexed { ordinal, action ->
            assertTrue(action.actionIntent.isImmutable)
            assertTrue(action.actionIntent.isBroadcast)

            val expectedIntent = Intent(application, FeedbackReceiver::class.java).apply {
                this.action = expectedAction(ordinal)
                data = android.net.Uri.parse("shakeguard://feedback/$eventId/$ordinal")
                putExtra(FeedbackReceiver.EXTRA_EVENT_ID, eventId)
            }
            val existing = PendingIntent.getBroadcast(
                application,
                ProtectionNotificationManager.requestCode(eventId, ordinal),
                expectedIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            assertNotNull(existing)
            assertEquals(application.packageName, action.actionIntent.creatorPackage)
        }
    }

    @Test
    fun deniedNotificationPermissionReturnsFalseBeforePublishing() {
        val manager = ProtectionNotificationManager(
            application,
            notificationManager,
            permissionChecker = { false },
        )

        assertFalse(
            manager.publish(
                eventId = 42_002L,
                sourcePackage = application.packageName,
                targetPackage = application.packageName,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertNull(notificationManager.getNotificationChannel(FeedbackReceiver.CHANNEL_ID))
        }
    }

    @Test
    fun manifestDeclaresPermissionAndNonExportedReceiver() {
        val receiverInfo = application.packageManager.getReceiverInfo(
            ComponentName(application, FeedbackReceiver::class.java),
            0,
        )
        assertFalse(receiverInfo.exported)

        val packageInfo = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(
            packageInfo.requestedPermissions.orEmpty().contains(
                "android.permission.POST_NOTIFICATIONS",
            ),
        )
    }

    @Test
    fun malformedExplicitAndImplicitIntentsDoNotModifyEvent() = runBlocking {
        val eventId = insertEvent(sourcePackage = "news", targetPackage = "store")
        val receiver = FeedbackReceiver()

        receiver.onReceive(application, Intent().apply {
            component = ComponentName(application, FeedbackReceiver::class.java)
            action = FeedbackReceiver.ACTION_ALLOW_PAIR
        })
        receiver.onReceive(application, Intent().apply {
            component = ComponentName(application, FeedbackReceiver::class.java)
            action = FeedbackReceiver.ACTION_ALLOW_PAIR
            putExtra(FeedbackReceiver.EXTRA_EVENT_ID, 0L)
        })
        receiver.onReceive(application, Intent().apply {
            component = ComponentName(application, FeedbackReceiver::class.java)
            action = FeedbackReceiver.ACTION_ALLOW_PAIR
            putExtra(FeedbackReceiver.EXTRA_EVENT_ID, -1L)
        })
        receiver.onReceive(application, Intent().apply {
            component = ComponentName(application, FeedbackReceiver::class.java)
            action = FeedbackReceiver.ACTION_ALLOW_PAIR
            putExtra(FeedbackReceiver.EXTRA_EVENT_ID, 42)
        })
        receiver.onReceive(application, Intent().apply {
            component = ComponentName(application, FeedbackReceiver::class.java)
            action = "com.shakeguard.action.UNKNOWN"
            putExtra(FeedbackReceiver.EXTRA_EVENT_ID, eventId)
        })
        receiver.onReceive(application, Intent(FeedbackReceiver.ACTION_ALLOW_PAIR).apply {
            putExtra(FeedbackReceiver.EXTRA_EVENT_ID, eventId)
        })

        delay(200L)
        assertNull(application.database.jumpEventDao().find(eventId)?.userFeedback)
        assertEquals(0, application.database.pairRuleDao().find("news", "store").size)
    }

    @Test
    fun allowPairPendingIntentIsIdempotentThroughSharedReceiver() = runBlocking {
        val sourcePackage = "news.feedback.test"
        val targetPackage = "store.feedback.test"
        application.database.protectedSourceDao().upsert(
            ProtectedSourceEntity(
                packageName = sourcePackage,
                enabled = true,
                windowMs = 5_000L,
                sourceLevelBlock = true,
                updatedAt = 1L,
                createdAt = 1L,
            ),
        )
        val eventId = insertEvent(sourcePackage, targetPackage)
        val manager = ProtectionNotificationManager(application, notificationManager)
        val notification = manager.buildNotification(eventId, sourcePackage, targetPackage)

        notification.actions[1].actionIntent.send()
        notification.actions[1].actionIntent.send()

        withTimeout(5_000L) {
            while (
                application.database.jumpEventDao().find(eventId)?.userFeedback != "ALLOW_PAIR" ||
                application.database.pairRuleDao().find(sourcePackage, targetPackage).size != 1
            ) {
                delay(50L)
            }
        }

        assertEquals(
            "ALLOW_PAIR",
            application.database.jumpEventDao().find(eventId)?.userFeedback,
        )
        assertEquals(1, application.database.pairRuleDao().find(sourcePackage, targetPackage).size)
    }

    private suspend fun insertEvent(sourcePackage: String, targetPackage: String): Long =
        application.database.jumpEventDao().insert(
            JumpEventEntity(
                sourcePackage = sourcePackage,
                targetPackage = targetPackage,
                elapsedMs = 100L,
                decision = "BLOCK",
                matchedRuleId = null,
                actionResult = "RETURNED",
                userFeedback = null,
                createdAt = System.currentTimeMillis(),
            ),
        )

    private fun expectedAction(ordinal: Int): String = when (ordinal) {
        0 -> FeedbackReceiver.ACTION_ALLOW_ONCE
        1 -> FeedbackReceiver.ACTION_ALLOW_PAIR
        2 -> FeedbackReceiver.ACTION_CONFIRM_AD
        3 -> FeedbackReceiver.ACTION_STOP_PROTECTING_SOURCE
        else -> error("Unexpected action ordinal $ordinal")
    }
}
