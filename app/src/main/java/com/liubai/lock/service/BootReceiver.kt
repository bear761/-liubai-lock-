package com.liubai.lock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：恢复使用统计；若处于锁定期，重建覆盖锁屏 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            LockForegroundService.start(context)
        }
    }
}
