package com.speakupi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import com.speakupi.R
import com.speakupi.util.UiTextTranslator

class ListenerForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fallbackChannelName = getString(R.string.foreground_channel_name)
        val fallbackTitle = getString(R.string.foreground_notification_title)
        val fallbackText = getString(R.string.foreground_notification_text)

        ensureChannel(fallbackChannelName)

        try {
            startForeground(NOTIFICATION_ID, buildNotification(fallbackTitle, fallbackText))
        } catch (e: Exception) {
            // The system can refuse a foreground start (e.g. background-start restrictions); the
            // notification listener still works on its own, so degrade quietly instead of crashing.
            stopSelf()
            return START_NOT_STICKY
        }

        UiTextTranslator.translateList(this, listOf(fallbackChannelName, fallbackTitle, fallbackText)) { translated ->
            ensureChannel(translated[0])
            val translatedNotification = buildNotification(translated[1], translated[2])
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, translatedNotification)
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun ensureChannel(channelName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "transaction_reader_reliability"
        const val NOTIFICATION_ID = 4300
    }
}
