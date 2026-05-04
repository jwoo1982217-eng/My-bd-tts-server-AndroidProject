package com.github.jing332.tts_server_android.service.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.github.jing332.common.utils.startForegroundCompat
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.MainActivity
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 进程保活服务
 * 通过多种机制防止应用被系统杀死（墓碑冻结）
 */
class KeepAliveService : Service() {

    companion object {
        const val TAG = "KeepAliveService"
        const val NOTIFICATION_CHANNEL_ID = "keep_alive_channel"
        const val NOTIFICATION_ID = 9999
        const val ACTION_KEEP_ALIVE = "ACTION_KEEP_ALIVE"
        const val ACTION_STOP_KEEP_ALIVE = "ACTION_STOP_KEEP_ALIVE"

        // 保活检查间隔（毫秒）
        const val CHECK_INTERVAL_MS = 5000L
        // 唤醒锁持有时间（毫秒）
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10分钟

        @Volatile
        var isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_KEEP_ALIVE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var keepAliveJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    // 屏幕状态监听 - 仅用于息屏时获取唤醒锁，不抢占音频焦点
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏幕关闭时，获取唤醒锁（不抢占音频焦点）
                    acquireWakeLock()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕开启时，释放唤醒锁
                    releaseWakeLock()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // 创建高优先级通知渠道
        createNotificationChannel()
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        // 注册屏幕状态监听
        registerScreenStateReceiver()
        // 启动保活机制
        startKeepAliveMechanism()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_KEEP_ALIVE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY // 被杀死后会自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        keepAliveJob?.cancel()
        releaseWakeLock()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {}

        // 如果配置仍需要保活，尝试重启服务
        if (SysTtsConfig.isKeepAliveEnabled) {
            sendBroadcast(Intent(ACTION_KEEP_ALIVE).apply {
                setPackage(packageName)
            })
        }
    }

    /**
     * 创建高优先级通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.keep_alive_service),
                NotificationManager.IMPORTANCE_HIGH // 高优先级，不易被系统清理
            ).apply {
                description = getString(R.string.keep_alive_service_desc)
                setShowBadge(false)
                // 设置为重要渠道，降低被系统清理的概率
                importance = NotificationManager.IMPORTANCE_HIGH
                // 启用振动和提示灯
                enableVibration(true)
                enableLights(true)
                lightColor = ContextCompat.getColor(this@KeepAliveService, R.color.md_theme_light_primary)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台通知
     */
    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_STOP_KEEP_ALIVE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setSmallIcon(R.drawable.ic_app_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setColor(ContextCompat.getColor(this@KeepAliveService, R.color.md_theme_light_primary))
            .addAction(
                R.drawable.ic_close,
                getString(R.string.stop),
                stopPendingIntent
            )
            .build()
    }

    /**
     * 注册屏幕状态监听
     */
    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    /**
     * 获取唤醒锁（不抢占音频焦点）
     */
    private fun acquireWakeLock() {
        if (wakeLock != null && wakeLock!!.isHeld) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TTS Server:KeepAliveWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    /**
     * 释放唤醒锁
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock!!.release()
            }
        } catch (_: Exception) {}
    }

    /**
     * 启动保活机制
     */
    private fun startKeepAliveMechanism() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                // 定期获取唤醒锁防止CPU休眠
                acquireWakeLock()

                // 释放唤醒锁，让CPU可以休眠一段时间
                delay(CHECK_INTERVAL_MS)
                releaseWakeLock()

                // 再次获取唤醒锁
                delay(1000)
                acquireWakeLock()
            }
        }
    }
}
