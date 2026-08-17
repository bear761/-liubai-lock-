package com.liubai.lock.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.liubai.lock.core.LockStateRepo

/**
 * 无障碍服务：
 * 1) 实时感知前台 App（TYPE_WINDOW_STATE_CHANGED 事件推送，毫秒级）
 * 2) 锁定期内检测到非白名单 App 进入前台 → 按 HOME 顶回（等效强制退出）
 *
 * 覆盖层统一由前台服务 [LockForegroundService] 维护，避免无障碍事件在解锁临界点
 * 重复创建覆盖层导致「卡住无法退出」。
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

        // 锁定期：非白名单 App 一进入前台 → 立即顶回桌面。
        // 覆盖层由前台服务 ticker / 精确解锁任务负责，不在此处重建。
        if (LockStateRepo.lockActiveMem && !LockStateRepo.isWhitelisted(this, pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
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
