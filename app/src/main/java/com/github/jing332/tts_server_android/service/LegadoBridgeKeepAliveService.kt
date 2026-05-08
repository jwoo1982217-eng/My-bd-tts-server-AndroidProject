package com.github.jing332.tts_server_android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class LegadoBridgeKeepAliveService : Service() {

    companion object {
        const val ACTION_START = "com.github.jing332.tts_server_android.jtts.action.LEGADO_BRIDGE_START"
        const val ACTION_STOP = "com.github.jing332.tts_server_android.jtts.action.LEGADO_BRIDGE_STOP"

        private const val CHANNEL_ID = "legado_bridge_keep_alive"
        private const val NOTIFICATION_ID = 72110

        fun start(context: Context) {
            val intent = Intent(context, LegadoBridgeKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LegadoBridgeKeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Legado TTS Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keep TTS Server running for Legado read aloud"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = if (applicationInfo.icon != 0) {
            applicationInfo.icon
        } else {
            android.R.drawable.stat_notify_sync
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(smallIcon)
            .setContentTitle("TTS Server 已连接阅读")
            .setContentText("阅读朗读时自动保持后台运行")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
