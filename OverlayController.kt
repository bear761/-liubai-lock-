package com.liubai.lock.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.liubai.lock.R

/**
 * 覆盖锁屏控制器：通过 WindowManager 添加全屏覆盖层（TYPE_APPLICATION_OVERLAY），
 * 盖住任何前台 App，内嵌「请稍作休息」弹窗。所有操作切换到主线程执行。
 */
object OverlayController {

    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var countdownText: TextView? = null
    private var popupRemainText: TextView? = null
    private var ring: ProgressBar? = null
    private var popup: View? = null

    val isVisible: Boolean get() = overlayView != null

    fun show(context: Context, remainMs: Long, totalMs: Long, withPopup: Boolean) {
        handler.post {
            val appCtx = context.applicationContext
            if (overlayView != null) {
                update(remainMs, totalMs)
                if (withPopup) popup?.visibility = View.VISIBLE
                return@post
            }
            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = LayoutInflater.from(appCtx).inflate(R.layout.overlay_lock, null)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                countdownText = view.findViewById(R.id.countdown)
                ring = view.findViewById(R.id.ring)
                popup = view.findViewById(R.id.restPopup)
                popupRemainText = view.findViewById(R.id.popupRemain)
                view.findViewById<Button>(R.id.popupOk).setOnClickListener {
                    popup?.visibility = View.GONE
                }
                view.findViewById<View>(R.id.breathe)
                    .startAnimation(AnimationUtils.loadAnimation(appCtx, R.anim.breathe))

                wm.addView(view, params)
                overlayView = view
                update(remainMs, totalMs)
                popup?.visibility = if (withPopup) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                // 无悬浮窗权限等情况：静默失败，等待用户授权后重试
            }
        }
    }

    fun updateCountdown(remainMs: Long, totalMs: Long) {
        handler.post { update(remainMs, totalMs) }
    }

    private fun update(remainMs: Long, totalMs: Long) {
        countdownText?.text = LockStateRepo.fmt(remainMs)
        popupRemainText?.text = "剩余 " + LockStateRepo.fmt(remainMs)
        val total = totalMs.coerceAtLeast(1L)
        ring?.progress = ((remainMs * 100) / total).toInt().coerceIn(0, 100)
    }

    fun hide(context: Context) {
        handler.post {
            try {
                overlayView?.let {
                    val wm = context.applicationContext
                        .getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(it)
                }
            } catch (_: Exception) {
            }
            overlayView = null
            countdownText = null
            popupRemainText = null
            ring = null
            popup = null
        }
    }
}
