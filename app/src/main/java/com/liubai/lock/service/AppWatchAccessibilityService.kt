package com.liubai.lock.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.liubai.lock.core.LockStateRepo
import com.liubai.lock.core.OverlayController

/**
 * 无障碍服务：
 * 1) 实时感知前台 App（TYPE_WINDOW_STATE_CHANGED 事件推送，毫秒级）
 * 2) 锁定期内检测到非白名单 App 进入前台 → 按 HOME 顶回（等效强制退出）+ 覆盖锁屏 + 弹窗
 */
class AppWatchAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AppWatchAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        LockStateRepo.foregroundPkg = pkg

        // 锁定期：非白名单 App 一进入前台 → 立即顶回桌面
        // 覆盖层由前台服务的 ticker / 精确解锁任务负责维护，避免无障碍事件在解锁临界点
        // 因为读取到旧的 lockActiveMem 而重新创建覆盖层导致「卡住无法退出」。
        if (LockStateRepo.lockActiveMem && !LockStateRepo.isWhitelisted(this, pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            handler.post {
                // 主线程再次校验：只有仍然处于锁定状态才允许重建覆盖层
                if (!LockStateRepo.lockActiveMem || !LockStateRepo.isLocked(this)) return@post
                val remain = LockStateRepo.getLockEnd(this) - System.currentTimeMillis()
                val total = LockStateRepo.getLockTotalMs(this).let { if (it > 0) it else LockStateRepo.lockDurationMs(this) }
                OverlayController.show(this, remain, total, withPopup = true)
            }
        }
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
