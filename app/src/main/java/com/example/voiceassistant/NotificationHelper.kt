package com.example.voiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * NotificationHelper
 * ------------------
 * Centralises all notification / notification-channel boilerplate so that
 * VoiceAssistantService never has to touch NotificationManager directly.
 *
 * Android 8+  → channels are mandatory.
 * Android 12+ → PendingIntent needs FLAG_IMMUTABLE or FLAG_MUTABLE.
 * Android 14+ → foregroundServiceType="microphone" is declared in the manifest.
 *
 * HOW TO USE:
 *   Call NotificationHelper.createChannel(context) once, e.g. in Application.onCreate()
 *   or at the top of Service.onCreate() — it is idempotent.
 *   Then call buildForegroundNotification() to get the Notification for startForeground().
 */
object NotificationHelper {

    const val CHANNEL_ID       = "voice_assistant_channel"
    const val NOTIFICATION_ID  = 1001
    private  const val CHANNEL_NAME    = "Voice Assistant Service"
    private  const val CHANNEL_DESC    = "Shows when the voice assistant is actively listening or processing"

    /**
     * Creates the notification channel (no-op on API < 26).
     * Safe to call multiple times — the system ignores duplicates.
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW   // LOW → no sound, no pop-up
            ).apply {
                description            = CHANNEL_DESC
                setShowBadge(false)    // don’t show dot on launcher icon
                lockscreenVisibility   = Notification.VISIBILITY_PUBLIC
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent foreground notification shown while the service is running.
     *
     * @param context  Any valid Context (Service works fine).
     * @param statusText  Short text shown in the notification body, e.g. “Listening…”
     * @return A fully configured [Notification] ready for [android.app.Service.startForeground].
     */
    fun buildForegroundNotification(context: Context, statusText: String = "Ready"): Notification {
        // Tapping the notification re-opens MainActivity
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(context, 0, openIntent, pendingFlags)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)  // replace with your own mic icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)           // cannot be swiped away while service is foreground
            .setSilent(true)            // never play a sound for this notification
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the text shown in the persistent notification without restarting the service.
     * Call this whenever the assistant state changes (Listening / Processing / Idle).
     */
    fun updateNotification(context: Context, statusText: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(context, statusText))
    }
}
