package com.shakeguard.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shakeguard.ShakeGuardApplication
import com.shakeguard.feedback.FeedbackCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class FeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val incoming = intent ?: return
        if (!isTrustedIntent(context, incoming)) return

        val command = commandFor(
            action = incoming.action,
            eventId = readEventId(incoming),
        ) ?: return
        val application = context.applicationContext as? ShakeGuardApplication ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(HANDLER_TIMEOUT_MS) {
                    application.feedbackHandler.handle(command)
                }
            } catch (cancellation: CancellationException) {
                Log.w(TAG, "Feedback handling cancelled", cancellation)
            } catch (cause: Exception) {
                Log.w(TAG, "Feedback handling failed", cause)
            } finally {
                try {
                    pendingResult.finish()
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    private fun isTrustedIntent(context: Context, intent: Intent?): Boolean {
        val component = intent?.component ?: return false
        return component.packageName == context.packageName &&
            component.className == FeedbackReceiver::class.java.name
    }

    private fun readEventId(intent: Intent): Long? {
        return try {
            intent.extras?.get(EXTRA_EVENT_ID) as? Long
        } catch (_: RuntimeException) {
            null
        }
    }

    companion object {
        internal const val CHANNEL_ID = "protection_feedback"
        internal const val ACTION_ALLOW_ONCE = "com.shakeguard.action.ALLOW_ONCE"
        internal const val ACTION_ALLOW_PAIR = "com.shakeguard.action.ALLOW_PAIR"
        internal const val ACTION_CONFIRM_AD = "com.shakeguard.action.CONFIRM_AD"
        internal const val ACTION_STOP_PROTECTING_SOURCE =
            "com.shakeguard.action.STOP_PROTECTING_SOURCE"
        internal const val EXTRA_EVENT_ID = "com.shakeguard.extra.EVENT_ID"
        internal const val HANDLER_TIMEOUT_MS = 8_000L

        internal fun commandFor(action: String?, eventId: Long?): FeedbackCommand? {
            if (eventId == null || eventId <= 0L) return null
            return when (action) {
                ACTION_ALLOW_ONCE -> FeedbackCommand.AllowOnce(eventId)
                ACTION_ALLOW_PAIR -> FeedbackCommand.AllowPair(eventId)
                ACTION_CONFIRM_AD -> FeedbackCommand.ConfirmAd(eventId)
                ACTION_STOP_PROTECTING_SOURCE -> FeedbackCommand.StopProtectingSource(eventId)
                else -> null
            }
        }

        private const val TAG = "ShakeGuardFeedback"
    }
}
