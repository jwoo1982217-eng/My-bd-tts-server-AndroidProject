package com.github.jing332.tts_server_android.service.keepalive

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.github.jing332.common.utils.toast
import com.github.jing332.tts_server_android.R

/**
 * 像素保活服务
 * 使用1像素悬浮窗保持进程存活
 * 屏幕关闭时显示，亮屏时隐藏，不打扰用户
 */
class PixelKeepAliveService : Service() {

    companion object {
        fun isRunning(): Boolean {
            return instance != null
        }

        fun canDrawOverlays(context: Context): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun requestOverlayPermission(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        fun start(context: Context) {
            if (!canDrawOverlays(context)) {
                context.toast("请先授予悬浮窗权限")
                // 跳转到权限设置
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            val intent = Intent(context, PixelKeepAliveService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PixelKeepAliveService::class.java)
            context.stopService(intent)
        }

        private var instance: PixelKeepAliveService? = null
    }

    private var windowManager: WindowManager? = null
    private var pixelView: View? = null
    private var screenStateReceiver: android.content.BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPixelKeepAlive()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPixelKeepAlive()
        instance = null
    }

    private fun startPixelKeepAlive() {
        // 注册屏幕状态监听
        screenStateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> showPixelView()
                    Intent.ACTION_SCREEN_ON -> hidePixelView()
                }
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)

        // 获取唤醒锁
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TTS Server:PixelKeepAlive"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10小时
        }

        // 初始状态根据屏幕状态决定
        val powerManagerSvc = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = powerManagerSvc.isScreenOn
        if (!isScreenOn) {
            showPixelView()
        }
    }

    private fun showPixelView() {
        if (pixelView != null) return

        try {
            val params = WindowManager.LayoutParams(
                1, 1,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END

            pixelView = View(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            windowManager?.addView(pixelView, params)
        } catch (_: Exception) {}
    }

    private fun hidePixelView() {
        try {
            pixelView?.let {
                windowManager?.removeView(it)
                pixelView = null
            }
        } catch (_: Exception) {}
    }

    private fun stopPixelKeepAlive() {
        hidePixelView()

        try {
            screenStateReceiver?.let {
                unregisterReceiver(it)
            }
            screenStateReceiver = null

            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }
}
