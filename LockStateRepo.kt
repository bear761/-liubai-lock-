package com.liubai.lock.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/**
 * 状态仓库：锁定截止时间、连续使用累计、白名单等，全部持久化到 SharedPreferences。
 * 进程被杀 / 手机重启后可据此恢复。
 */
object LockStateRepo {

    private const val PREFS = "liubai_state"

    private const val K_LOCK_END = "lock_end"
    private const val K_USAGE = "usage_accum_ms"
    private const val K_LAST_SCREEN_OFF = "last_screen_off_at"
    private const val K_THRESHOLD = "threshold_min"
    private const val K_LOCK_DURATION = "lock_duration_min"
    private const val K_LOCK_TOTAL = "lock_total_ms"
    private const val K_WHITELIST = "whitelist"

    const val DEFAULT_THRESHOLD_MIN = 25
    const val DEFAULT_LOCK_MIN = 5

    /** 灭屏满 2 分钟才清零使用时长（防按电源键洗白进度） */
    const val SCREEN_CLEAR_MS = 2 * 60 * 1000L

    /** 运行时前台包名（由无障碍服务写入） */
    @Volatile
    var foregroundPkg: String? = null

    /** 运行时锁定内存标志（同步读写，避免 SharedPreferences apply 竞态导致覆盖层被反复重建） */
    @Volatile
    var lockActiveMem: Boolean = false

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 锁定状态 ----
    fun getLockEnd(ctx: Context): Long = p(ctx).getLong(K_LOCK_END, 0L)
    fun setLockEnd(ctx: Context, v: Long) { p(ctx).edit().putLong(K_LOCK_END, v).apply() }
    fun isLocked(ctx: Context): Boolean = getLockEnd(ctx) > System.currentTimeMillis()

    // ---- 使用累计 ----
    fun getUsageAccumMs(ctx: Context): Long = p(ctx).getLong(K_USAGE, 0L)
    fun setUsageAccumMs(ctx: Context, v: Long) { p(ctx).edit().putLong(K_USAGE, v.coerceAtLeast(0L)).apply() }
    fun addUsageMs(ctx: Context, delta: Long) { setUsageAccumMs(ctx, getUsageAccumMs(ctx) + delta) }

    fun getLastScreenOffAt(ctx: Context): Long = p(ctx).getLong(K_LAST_SCREEN_OFF, 0L)
    fun setLastScreenOffAt(ctx: Context, v: Long) { p(ctx).edit().putLong(K_LAST_SCREEN_OFF, v).apply() }

    // ---- 设置 ----
    fun getThresholdMin(ctx: Context): Int = p(ctx).getInt(K_THRESHOLD, DEFAULT_THRESHOLD_MIN)
    fun setThresholdMin(ctx: Context, v: Int) { p(ctx).edit().putInt(K_THRESHOLD, v.coerceIn(1, 240)).apply() }
    fun thresholdMs(ctx: Context): Long = getThresholdMin(ctx) * 60_000L

    fun getLockDurationMin(ctx: Context): Int = p(ctx).getInt(K_LOCK_DURATION, DEFAULT_LOCK_MIN)
    fun setLockDurationMin(ctx: Context, v: Int) { p(ctx).edit().putInt(K_LOCK_DURATION, v.coerceIn(1, 60)).apply() }
    fun lockDurationMs(ctx: Context): Long = getLockDurationMin(ctx) * 60_000L

    fun getLockTotalMs(ctx: Context): Long = p(ctx).getLong(K_LOCK_TOTAL, 0L)
    fun setLockTotalMs(ctx: Context, v: Long) { p(ctx).edit().putLong(K_LOCK_TOTAL, v).apply() }

    // ---- 白名单 ----
    fun getWhitelist(ctx: Context): MutableSet<String> {
        return p(ctx).getStringSet(K_WHITELIST, null) ?: defaultWhitelist(ctx)
    }

    fun setWhitelist(ctx: Context, set: Set<String>) {
        p(ctx).edit().putStringSet(K_WHITELIST, set).apply()
    }

    fun isWhitelisted(ctx: Context, pkg: String): Boolean = getWhitelist(ctx).contains(pkg)

    /** 默认白名单：自身、系统 UI、桌面、电话、短信、相机 */
    fun defaultWhitelist(ctx: Context): MutableSet<String> {
        val pm = ctx.packageManager
        val set = mutableSetOf(ctx.packageName, "com.android.systemui", "android")
        fun addByIntent(intent: Intent) {
            try {
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.let { set.add(it) }
            } catch (_: Exception) {
            }
        }
        addByIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        addByIntent(Intent(Intent.ACTION_DIAL))
        addByIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        addByIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        return set
    }

    fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 999) / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }
}
