package com.openstation.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/**
 * System-wide volume OSD & status pill rendered via TYPE_APPLICATION_OVERLAY so it appears on
 * top of all third-party apps (YouTube, Kinopoisk, players) and the launcher itself.
 * Look: VolumeHud.dc.html ([VolumeHudView]).
 */
class VolumeOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "VolumeOverlayMgr"
        private const val AUTO_HIDE_VOL_MS = 2000L
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val easeIn = AccelerateInterpolator(1.3f)

    private var rootContainer: FrameLayout? = null
    private var hud: VolumeHudView? = null

    private var attached = false
    private var hiding = false

    private val hideRunnable = Runnable {
        hideOverlay(immediate = false)
    }

    /** Glass theme: the author's OSD does all the work (chosen once; theme switch recreates us). */
    private val glassImpl = if (Theme.glass) com.openstation.launcher.glass.VolumeOverlayManager(context) else null

    fun start() {
        glassImpl?.start() // Ambient overlay attaches lazily on first volume/status event
    }

    fun stop() {
        glassImpl?.stop()
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            removeWindowNow()
        }
    }

    fun showVolume(percent: Int, muted: Boolean) {
        glassImpl?.let { it.showVolume(percent, muted); return }
        mainHandler.post {
            ensureViews()
            hud?.bind(percent, muted)
            showWindowIfNeeded()
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, AUTO_HIDE_VOL_MS)
        }
    }

    fun showToastPill(title: String, subtitle: String, danger: Boolean = false, durationMs: Long = 2800L) {
        glassImpl?.let { it.showToastPill(title, subtitle, if (danger) "#F87171" else "#E8C686", durationMs); return }
        mainHandler.post {
            ensureViews()
            hud?.bindToast(title, subtitle, danger)
            showWindowIfNeeded()
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, durationMs.coerceIn(1000L, 7000L))
        }
    }

    private fun ensureViews() {
        if (rootContainer != null) return
        val container = FrameLayout(context)
        val v = VolumeHudView(context)
        // Fixed 520x96 pill, 40px from the top edge (VolumeHud.dc.html)
        container.addView(v, FrameLayout.LayoutParams(context.dp(520), context.dp(96), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = context.dp(40)
        })
        rootContainer = container
        hud = v
    }

    private fun showWindowIfNeeded() {
        val container = rootContainer ?: return
        val card = hud ?: return
        if (!attached) {
            if (!canDrawOverlaysSafe()) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; volume OSD skipped")
                return
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            try {
                card.animate().cancel()
                card.alpha = 0f
                card.translationY = -context.dpf(12)
                wm.addView(container, lp)
                attached = true
                hiding = false
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(160L)
                    .setInterpolator(easeOut)
                    .start()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach volume overlay: ${t.message}")
                attached = false
            }
        } else if (hiding) {
            hiding = false
            card.animate().cancel()
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(easeOut)
                .start()
        }
    }

    private fun hideOverlay(immediate: Boolean) {
        if (!attached) return
        val card = hud
        if (immediate || card == null) {
            removeWindowNow()
            return
        }
        if (hiding) return
        hiding = true
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .translationY(-context.dpf(12))
            .setDuration(220L)
            .setInterpolator(easeIn)
            .withEndAction {
                if (hiding) removeWindowNow()
            }
            .start()
    }

    private fun removeWindowNow() {
        hiding = false
        val container = rootContainer
        hud?.animate()?.cancel()
        if (attached && container != null) {
            try {
                wm.removeViewImmediate(container)
            } catch (_: Throwable) {
                try { wm.removeView(container) } catch (_: Throwable) {}
            }
        }
        attached = false
    }

    private fun canDrawOverlaysSafe(): Boolean =
        try {
            Settings.canDrawOverlays(context)
        } catch (_: Throwable) {
            false
        }
}
