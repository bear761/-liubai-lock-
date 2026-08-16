package com.liubai.lock.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
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

        // 锁定期：非白名单 App 一进入前台 → 立即顶回 + 覆盖 + 弹窗
        if (LockStateRepo.isLocked(this) && !LockStateRepo.isWhitelisted(this, pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val remain = LockStateRepo.getLockEnd(this) - System.currentTimeMillis()
            OverlayController.show(this, remain, LockStateRepo.lockDurationMs(this), withPopup = true)
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
