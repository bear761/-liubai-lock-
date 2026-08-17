package com.liubai.lock.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.liubai.lock.R
import com.liubai.lock.core.LockStateRepo
import com.liubai.lock.core.OverlayController

/**
 * 常驻前台服务 —— 「连续使用时长统计引擎」：
 * - 屏幕亮 + 前台为非白名单 App → 每秒累计使用时长
 * - 灭屏满 2 分钟 / 锁定结束 → 清零重新统计
 * - 累计 ≥ 阈值 → 触发全局锁定 5 分钟（覆盖锁屏 + HOME 顶回 + 弹窗）
 * - 常驻通知实时显示进度
 */
class LockForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "liubai_monitor"
        const val NOTI_ID = 1001
        const val ACTION_STOP = "com.liubai.lock.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, LockForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = true
    private var screenOffAt = 0L
    private var lastNotiText = ""

    /** 精确到 lockEnd 的解锁任务，避免 ticker 250ms 粒度在最后 <1s 时漏掉解锁 */
    private var unlockRunnable: Runnable? = null

    private val ticker = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (_: Exception) {
            }
            // 锁定期缩短心跳到 250ms，避免最后不足 1 秒的余量卡住不退出
            val interval = if (LockStateRepo.isLocked(this@LockForegroundService)) 250L else 1000L
            handler.postDelayed(this, interval)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    screenOffAt = System.currentTimeMillis()
                    LockStateRepo.setLastScreenOffAt(context, screenOffAt)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    // 灭屏满 2 分钟 → 使用时长清零
                    if (screenOffAt > 0 &&
                        System.currentTimeMillis() - screenOffAt >= LockStateRepo.SCREEN_CLEAR_MS
                    ) {
                        LockStateRepo.setUsageAccumMs(context, 0L)
                    }
                    screenOffAt = 0L
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val text = if (LockStateRepo.isLocked(this)) "休息中" else "正在统计使用时长"
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, buildNotification(text))
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OverlayController.hide(this)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** 每秒心跳：统计使用时长 / 维持锁定 */
    private fun onTick() {
        if (LockStateRepo.isLocked(this)) {
            val remain = LockStateRepo.getLockEnd(this) - System.currentTimeMillis()
            if (remain <= 0) {
                doUnlock()
            } else {
                // 进程重启 / 开机恢复等情况：重设内存标志并重建覆盖层（不重复弹窗）
                if (!LockStateRepo.lockActiveMem) LockStateRepo.lockActiveMem = true
                val total = LockStateRepo.getLockTotalMs(this).let { if (it > 0) it else LockStateRepo.lockDurationMs(this) }
                if (OverlayController.isVisible) {
                    OverlayController.updateCountdown(remain, total)
                } else {
                    OverlayController.show(this, remain, total, withPopup = false)
                }
                updateNotification("休息中 · 剩余 " + LockStateRepo.fmt(remain))
                if (unlockRunnable == null) scheduleUnlock()
            }
        } else {
            // 统计：屏幕亮 + 前台为非白名单 App
            val fg = LockStateRepo.foregroundPkg
            val counting = screenOn && fg != null && !LockStateRepo.isWhitelisted(this, fg)
            if (counting) {
                LockStateRepo.addUsageMs(this, 1000L)
                if (LockStateRepo.getUsageAccumMs(this) >= LockStateRepo.thresholdMs(this)) {
                    triggerLock()
                    return
                }
            }
            updateNotification(
                "已连续使用 " + LockStateRepo.fmt(LockStateRepo.getUsageAccumMs(this)) +
                        " / " + LockStateRepo.fmt(LockStateRepo.thresholdMs(this))
            )
        }
    }

    /** 触发全局锁定 */
    private fun triggerLock() {
        val total = LockStateRepo.lockDurationMs(this)
        LockStateRepo.setLockTotalMs(this, total)
        LockStateRepo.setLockEnd(this, System.currentTimeMillis() + total)
        LockStateRepo.lockActiveMem = true
        // 顶回桌面（等效强制退出当前 App）
        AppWatchAccessibilityService.instance
            ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // 全屏覆盖 + 「请稍作休息」弹窗
        OverlayController.show(this, total, total, withPopup = true)
        updateNotification("休息中 · 剩余 " + LockStateRepo.fmt(total))
        scheduleUnlock()
    }

    /** 统一解锁：清内存标志、清持久化状态、移除覆盖层 */
    private fun doUnlock() {
        unlockRunnable?.let { handler.removeCallbacks(it) }
        unlockRunnable = null
        LockStateRepo.lockActiveMem = false
        LockStateRepo.setUsageAccumMs(this, 0L)
        LockStateRepo.setLockEnd(this, 0L)
        LockStateRepo.setLockTotalMs(this, 0L)
        OverlayController.hide(this)
        updateNotification("已解锁，重新开始统计")
    }

    /** 在 lockEnd 到点时精确执行解锁，避免依赖 ticker 轮询 */
    private fun scheduleUnlock() {
        val end = LockStateRepo.getLockEnd(this)
        if (end <= 0) return
        unlockRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (LockStateRepo.isLocked(this)) doUnlock()
        }
        unlockRunnable = r
        val remain = end - System.currentTimeMillis()
        if (remain > 0) handler.postDelayed(r, remain) else handler.post(r)
    }

    // ---- 通知 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "使用统计", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "显示连续使用时长与锁定状态"
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("留白 · 守护中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotiText) return
        lastNotiText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_ID, buildNotification(text))
    }
}
