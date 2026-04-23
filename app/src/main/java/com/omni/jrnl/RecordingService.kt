package com.omni.jrnl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service, который удерживает запись в фоне.
 * Показывает постоянное уведомление «Recording…» пока идёт запись.
 * Совместим с Android 10+ (API 29+).
 *
 * На Android 14+ (API 34) сервис объявлен с foregroundServiceType="microphone",
 * что требует FOREGROUND_SERVICE_MICROPHONE в манифесте.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG            = "RecordingService"
        const  val CHANNEL_ID            = "jrnl_recording_channel"
        const  val NOTIFICATION_ID       = 42
        private const val ACTION_STOP    = "com.omni.jrnl.ACTION_STOP_RECORDING"

        /**
         * true пока сервис работает.
         * Используется в MainActivity для обнаружения зависшего сервиса после смерти процесса.
         * Сбрасывается в onDestroy() — т.е. не переживает смерть процесса (что правильно).
         */
        @Volatile var isRunning: Boolean = false
            private set

        /** Запускает сервис. Безопасно вызывать несколько раз. */
        fun start(context: Context) {
            Log.d(TAG, "start() requested")
            val intent = Intent(context, RecordingService::class.java)
            context.startForegroundService(intent)
        }

        /** Останавливает сервис и убирает уведомление. */
        fun stop(context: Context) {
            Log.d(TAG, "stop() requested")
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "onCreate() isRunning=true")
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received from notification")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "onDestroy() isRunning=false — removing notification")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Needle")
            .setContentText("Recording in progress · voice capture active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Needle · Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Needle app — shown while recording is in progress"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        Log.d(TAG, "Notification channel ensured: $CHANNEL_ID")
    }
}
