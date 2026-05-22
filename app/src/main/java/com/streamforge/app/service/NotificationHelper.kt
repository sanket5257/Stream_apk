package com.streamforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.streamforge.app.R

/**
 * Phase 4A: Helper for creating and managing streaming notifications.
 */
object NotificationHelper {
    
    const val CHANNEL_ID = "streamforge_live"
    const val NOTIFICATION_ID = 1001
    
    /**
     * Ensure the notification channel exists (Android 8.0+).
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Streaming",
                NotificationManager.IMPORTANCE_LOW // Low = no sound/vibration
            ).apply {
                description = "Notifications shown while streaming is active"
                setShowBadge(false)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Build the ongoing notification shown while streaming.
     */
    fun buildLiveNotification(
        context: Context,
        stopPendingIntent: PendingIntent,
        returnPendingIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("StreamForge - Live")
            .setContentText("Streaming to YouTube")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true) // Cannot be dismissed by swipe
            .setContentIntent(returnPendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Build notification for reconnecting state.
     */
    fun buildReconnectingNotification(
        context: Context,
        stopPendingIntent: PendingIntent,
        returnPendingIntent: PendingIntent,
        attempt: Int
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("StreamForge - Reconnecting...")
            .setContentText("Attempt $attempt of 3")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(returnPendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
