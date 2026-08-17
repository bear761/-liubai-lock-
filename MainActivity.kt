package com.liubai.lock

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.liubai.lock.core.LockStateRepo
import com.liubai.lock.service.AppWatchAccessibilityService
import com.liubai.lock.service.LockForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var usageText: TextView
    private lateinit var usageProgress: ProgressBar
    private lateinit var thresholdInput: EditText
    private lateinit var durationInput: EditText

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        usageText = findViewById(R.id.usageText)
        usageProgress = findViewById(R.id.usageProgress)
        thresholdInput = findViewById(R.id.thresholdInput)
        durationInput = findViewById(R.id.durationInput)

        thresholdInput.setText(LockStateRepo.getThresholdMin(this).toString())
        durationInput.setText(LockStateRepo.getLockDurationMin(this).toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { ensurePermissionsThenStart() }
        findViewById<Button>(R.id.btnTestLock).setOnClickListener { testLock() }
        findViewById<Button>(R.id.btnWhitelist).setOnClickListener { showWhitelistDialog() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---- 状态与权限 ----

    private fun refreshStatus() {
        val sb = StringBuilder()
        fun line(ok: Boolean, label: String) {
            sb.append(if (ok) "✅ " else "❌ ").append(label).append('\n')
        }
        line(Settings.canDrawOverlays(this), "悬浮窗权限（全屏锁屏覆盖）")
        line(isAccessibilityOn(), "无障碍服务（前台检测 + 顶回）")
        line(hasUsageAccess(), "使用情况访问（降级兜底，可选）")
        statusText.text = sb.toString().trim()

        if (LockStateRepo.isLocked(this)) {
            val remain = LockStateRepo.getLockEnd(this) - System.currentTimeMillis()
            usageText.text = "🔒 休息中 · 剩余 " + LockStateRepo.fmt(remain)
            usageProgress.progress = 100
        } else {
            val usage = LockStateRepo.getUsageAccumMs(this)
            val total = LockStateRepo.thresholdMs(this)
            usageText.text = "已连续使用 " + LockStateRepo.fmt(usage) + " / " + LockStateRepo.fmt(total)
            usageProgress.progress = ((usage * 100) / total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        }
    }

    private fun isAccessibilityOn(): Boolean {
        if (AppWatchAccessibilityService.isRunning) return true
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.contains("$packageName/")
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun ensurePermissionsThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            toastAndOpen("请先授予悬浮窗权限（用于全屏锁屏）", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            return
        }
        if (!isAccessibilityOn()) {
            toastAndOpen("请在无障碍设置中开启「留白」", Settings.ACTION_ACCESSIBILITY_SETTINGS)
            return
        }
        LockForegroundService.start(this)
        Toast.makeText(this, "守护已开始：连续使用 ${LockStateRepo.getThresholdMin(this)} 分钟后上锁", Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun toastAndOpen(msg: String, action: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(action))
        }
    }

    // ---- 操作 ----

    private fun saveSettings() {
        val threshold = thresholdInput.text.toString().toIntOrNull() ?: LockStateRepo.DEFAULT_THRESHOLD_MIN
        val duration = durationInput.text.toString().toIntOrNull() ?: LockStateRepo.DEFAULT_LOCK_MIN
        LockStateRepo.setThresholdMin(this, threshold)
        LockStateRepo.setLockDurationMin(this, duration)
        thresholdInput.setText(threshold.toString())
        durationInput.setText(duration.toString())
        Toast.makeText(this, "已保存：使用 $threshold 分钟 → 上锁 $duration 分钟", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun testLock() {
        if (!Settings.canDrawOverlays(this)) {
            toastAndOpen("请先授予悬浮窗权限", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            return
        }
        val total = 30_000L
        LockStateRepo.setLockTotalMs(this, total)
        LockStateRepo.setLockEnd(this, System.currentTimeMillis() + total)
        LockStateRepo.lockActiveMem = true
        LockForegroundService.start(this)
        Toast.makeText(this, "测试锁定 30 秒已触发", Toast.LENGTH_SHORT).show()
    }

    private fun showWhitelistDialog() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedBy {
                (it.loadLabel(pm).toString())
            }

        val whitelist = LockStateRepo.getWhitelist(this)
        val names = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        val checked = apps.map { whitelist.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("锁定期放行的 App（白名单）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val set = whitelist.toMutableSet()
                apps.forEachIndexed { i, app: ApplicationInfo ->
                    if (checked[i]) set.add(app.packageName) else set.remove(app.packageName)
                }
                // 核心包名始终放行
                set.add(packageName); set.add("com.android.systemui"); set.add("android")
                LockStateRepo.setWhitelist(this, set)
                Toast.makeText(this, "白名单已更新（${set.size} 项）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
