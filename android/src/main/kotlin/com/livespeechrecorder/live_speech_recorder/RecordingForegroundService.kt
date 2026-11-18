package com.livespeechrecorder.live_speech_recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the process eligible for microphone capture while the app is in the background.
 *
 * All companion state is confined to the main thread.
 */
class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Recording audio"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Microphone is in use"
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(title, text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: RuntimeException) {
            startPending = false
            stopRequested = false
            stopSelf()
            return START_NOT_STICKY
        }
        startPending = false
        if (stopRequested) {
            stopRequested = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio recording", NotificationManager.IMPORTANCE_LOW)
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "live_speech_recorder_recording"
        private const val NOTIFICATION_ID = 41873
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        // Stopping before startForeground() runs would crash the app, so a stop that arrives
        // early is deferred until onStartCommand has completed.
        private var startPending = false
        private var stopRequested = false

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            stopRequested = false
            startPending = true
            try {
                context.startForegroundService(intent)
            } catch (e: RuntimeException) {
                startPending = false
                throw e
            }
        }

        fun stop(context: Context) {
            if (startPending) {
                stopRequested = true
            } else {
                context.stopService(Intent(context, RecordingForegroundService::class.java))
            }
        }
    }
}
