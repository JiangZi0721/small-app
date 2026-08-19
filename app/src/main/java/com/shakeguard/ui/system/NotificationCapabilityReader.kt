package com.shakeguard.ui.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class NotificationCapability { Available, Unavailable }

fun interface NotificationCapabilityReader {
    suspend fun read(): NotificationCapability
}

class AndroidNotificationCapabilityReader(private val context: Context) : NotificationCapabilityReader {
    override suspend fun read(): NotificationCapability = try {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = NotificationManagerCompat.from(context).getNotificationChannel(
            CHANNEL_ID,
        )?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        if (permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled) {
            NotificationCapability.Available
        } else {
            NotificationCapability.Unavailable
        }
    } catch (_: RuntimeException) {
        NotificationCapability.Unavailable
    }
}

private const val CHANNEL_ID = "protection_feedback"
