package com.shakeguard.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.shakeguard.R

class ProtectionNotificationManager(
    context: Context,
    private val notificationManager: NotificationManager =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?: error("NotificationManager is unavailable"),
    private val permissionChecker: () -> Boolean = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    },
) {
    private val context = context.applicationContext ?: context

    fun ensureChannel(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        return try {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    FeedbackReceiver.CHANNEL_ID,
                    context.getString(R.string.notification_channel_protection_feedback),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun publish(eventId: Long, sourcePackage: String, targetPackage: String): Boolean {
        return try {
            if (eventId <= 0L || !canPublish()) return false
            if (!ensureChannel()) return false
            notificationManager.notify(
                notificationId(eventId),
                buildNotification(eventId, sourcePackage, targetPackage),
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    internal fun buildNotification(
        eventId: Long,
        sourcePackage: String,
        targetPackage: String,
    ): Notification {
        require(eventId > 0L) { "eventId must be positive" }

        val sourceLabel = applicationLabel(sourcePackage)
        val targetLabel = applicationLabel(targetPackage)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, FeedbackReceiver.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(R.drawable.ic_feedback_notification)
            .setContentTitle(context.getString(R.string.notification_title_protection_blocked))
            .setContentText(
                context.getString(
                    R.string.notification_content_protection_blocked,
                    sourceLabel,
                    targetLabel,
                ),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)

        actionSpecs.forEach { spec ->
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_feedback_notification),
                    context.getString(spec.labelRes),
                    createPendingIntent(eventId, spec),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun canPublish(): Boolean = try {
        permissionChecker() && notificationManager.areNotificationsEnabled()
    } catch (_: RuntimeException) {
        false
    }

    private fun applicationLabel(packageName: String): String = try {
        val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(applicationInfo).toString()
            .takeIf { it.isNotBlank() }
            ?: packageName
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    } catch (_: RuntimeException) {
        packageName
    }

    private fun createPendingIntent(eventId: Long, spec: ActionSpec): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(eventId, spec.ordinal),
            feedbackIntent(eventId, spec),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun feedbackIntent(eventId: Long, spec: ActionSpec): Intent =
        Intent(context, FeedbackReceiver::class.java).apply {
            action = spec.action
            data = Uri.parse("shakeguard://feedback/$eventId/${spec.ordinal}")
            putExtra(FeedbackReceiver.EXTRA_EVENT_ID, eventId)
        }

    private fun notificationId(eventId: Long): Int {
        val folded = (eventId xor (eventId ushr 32)) and Long.MAX_VALUE
        return (folded % Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    private data class ActionSpec(
        val action: String,
        val ordinal: Int,
        val labelRes: Int,
    )

    internal companion object {
        const val ACTION_COUNT = 4L
        private val actionSpecs = listOf(
            ActionSpec(
                FeedbackReceiver.ACTION_ALLOW_ONCE,
                ordinal = 0,
                labelRes = R.string.notification_action_allow_once,
            ),
            ActionSpec(
                FeedbackReceiver.ACTION_ALLOW_PAIR,
                ordinal = 1,
                labelRes = R.string.notification_action_allow_pair,
            ),
            ActionSpec(
                FeedbackReceiver.ACTION_CONFIRM_AD,
                ordinal = 2,
                labelRes = R.string.notification_action_confirm_ad,
            ),
            ActionSpec(
                FeedbackReceiver.ACTION_STOP_PROTECTING_SOURCE,
                ordinal = 3,
                labelRes = R.string.notification_action_stop_protecting_source,
            ),
        )

        fun requestCode(eventId: Long, actionOrdinal: Int): Int {
            require(eventId > 0L) { "eventId must be positive" }
            require(actionOrdinal in 0..3) { "actionOrdinal must be between 0 and 3" }
            val folded = (eventId xor (eventId ushr 32)) and Long.MAX_VALUE
            val eventBucket = folded % ((Int.MAX_VALUE.toLong() - ACTION_COUNT) / ACTION_COUNT)
            return (eventBucket * ACTION_COUNT + actionOrdinal + 1L).toInt()
        }
    }
}
