package com.openstation.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Floating TYPE_APPLICATION_OVERLAY bottom bar displaying the live animated Alice sphere
 * and live/recent assistant response text on top of any foreground Android app.
 * Automatically stays hidden while MainActivity is foreground (since MainActivity renders
 * the unified morphing orb & dock bar natively).
 */
class AliceOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "AliceOverlayMgr"
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val easeIn = AccelerateInterpolator(1.3f)

    private var rootContainer: FrameLayout? = null
    private var dockCard: LinearLayout? = null
    private var tvState: TextView? = null
    private var tvUserHint: TextView? = null
    private var tvReply: TextView? = null

    @Volatile
    private var launcherForeground = false
    private var attached = false
    private var hiding = false
    private var lastLingerReplyKey = ""
    private var dismissScheduled = false

    private val dismissRunnable = Runnable {
        dismissScheduled = false
        hideOverlay(immediate = false)
    }

    private val stateListener = LiveOrbState.StateListener {
        evaluateVisibility()
    }

    private val glassImpl = if (Theme.glass) com.openstation.launcher.glass.AliceOverlayManager(context) else null

    fun start() {
        glassImpl?.let { it.start(); return }
        mainHandler.post {
            LiveOrbState.addListener(stateListener)
            evaluateVisibility()
        }
    }

    fun stop() {
        glassImpl?.let { it.stop(); return }
        mainHandler.post {
            LiveOrbState.removeListener(stateListener)
            mainHandler.removeCallbacks(dismissRunnable)
            dismissScheduled = false
            removeWindowNow()
        }
    }

    fun setLauncherForeground(fg: Boolean) {
        glassImpl?.let { it.setLauncherForeground(fg); return }
        launcherForeground = fg
        mainHandler.post {
            if (fg) {
                // Immediately yield to MainActivity's integrated morphing sphere & dock
                mainHandler.removeCallbacks(dismissRunnable)
                dismissScheduled = false
                hideOverlay(immediate = true)
            } else {
                evaluateVisibility()
            }
        }
    }

    fun evaluateVisibility() {
        glassImpl?.let { it.evaluateVisibility(); return }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { evaluateVisibility() }
            return
        }

        if (launcherForeground) {
            if (attached) hideOverlay(immediate = true)
            return
        }

        val st = LiveOrbState.assistantState
        val isActive = (st == "connecting" || st == "listening" || st == "thinking" ||
            st == "speaking" || st == "error")
        val now = SystemClock.elapsedRealtime()
        val display = LiveOrbState.bestDisplayText()
        val recentTurn = (now - max(LiveOrbState.lastActiveAtMs, LiveOrbState.lastReplyAtMs)) < 2600L &&
            display.isNotBlank()

        if (isActive) {
            mainHandler.removeCallbacks(dismissRunnable)
            dismissScheduled = false
            lastLingerReplyKey = ""
            showOrUpdateOverlay()
        } else if (attached || recentTurn) {
            showOrUpdateOverlay()
            val key = "$display|${LiveOrbState.lastReplyAtMs}"
            if (!dismissScheduled || key != lastLingerReplyKey) {
                lastLingerReplyKey = key
                mainHandler.removeCallbacks(dismissRunnable)
                dismissScheduled = true
                val lingerMs = if (display.isNotBlank()) 3400L else 850L
                mainHandler.postDelayed(dismissRunnable, lingerMs)
            }
        }
    }

    private fun dp(v: Int): Int = context.dp(v) // same 1280x720 mockup scale as the launcher screens

    private fun ensureViews() {
        if (rootContainer != null) return

        // AliceOverlay.dc.html: bottom glass bar (left/right 90, bottom 44, height 128, radius 32)
        val container = FrameLayout(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rounded(Color.argb(199, 14, 14, 20), dp(32).toFloat())
            elevation = dp(20).toFloat()
            setPadding(dp(24), 0, dp(32), 0)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                if (LiveOrbState.assistantState != "idle") {
                    postQuickCommand("http://127.0.0.1:8080/api/assistant/stop")
                } else {
                    mainHandler.removeCallbacks(dismissRunnable)
                    dismissScheduled = false
                    hideOverlay(immediate = false)
                }
            }
        }

        val orb = AliceOrbView(context).apply {
            compactGlowBoost = 1f
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(24) }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val stateTv = context.eyebrow("Алиса · слушаю", Theme.accent, 13f)
        val replyTv = context.label("", 26f, Theme.TEXT, 600, lines = 2).apply { setLineSpacing(0f, 1.15f) }
        textCol.addView(stateTv)
        textCol.addView(replyTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        val userTv = context.label("", 15f, Theme.alpha(Theme.TEXT, 0.55f)).apply {
            maxWidth = dp(320)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16)
            }
        }

        card.addView(orb)
        card.addView(textCol)
        card.addView(userTv)

        container.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128), Gravity.BOTTOM).apply {
                leftMargin = dp(90)
                rightMargin = dp(90)
                bottomMargin = dp(44)
            }
        )

        rootContainer = container
        dockCard = card
        tvState = stateTv
        tvUserHint = userTv
        tvReply = replyTv
    }

    private fun bindState() {
        val st = LiveOrbState.assistantState
        val userTxt = LiveOrbState.liveUserText.trim()
        val display = LiveOrbState.bestDisplayText()

        val (stateLabel, fallbackReply) = when (st) {
            "connecting" -> "Алиса · подключаюсь" to "Подключаю голосовой поток…"
            "listening" -> "Алиса · слушаю" to (if (userTxt.isNotEmpty()) "«$userTxt»" else "Говорите…")
            "thinking" -> "Алиса · думаю" to (if (userTxt.isNotEmpty()) "«$userTxt»" else "Формирую ответ…")
            "speaking" -> "Алиса · отвечает" to "Отвечаю…"
            "error" -> "Алиса · ошибка" to "Не удалось выполнить запрос"
            else -> "Алиса" to ""
        }
        tvState?.text = stateLabel.uppercase()
        tvState?.setTextColor(if (st == "error") Theme.DANGER_FG else Theme.accent)

        if (userTxt.isNotEmpty() && display != "«$userTxt»" && st != "listening" && st != "thinking") {
            tvUserHint?.text = "«$userTxt»"
            tvUserHint?.visibility = View.VISIBLE
        } else {
            tvUserHint?.text = ""
            tvUserHint?.visibility = View.GONE
        }

        val bodyText = display.ifEmpty { fallbackReply }
        tvReply?.text = bodyText
        tvReply?.visibility = if (bodyText.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showOrUpdateOverlay() {
        ensureViews()
        bindState()
        val container = rootContainer ?: return
        val card = dockCard ?: return

        if (!attached) {
            if (!canDrawOverlaysSafe()) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted yet; cannot show system overlay")
                return
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(190),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            try {
                card.animate().cancel()
                card.alpha = 0f
                card.translationY = dp(38).toFloat()
                card.scaleX = 0.94f
                card.scaleY = 0.94f
                wm.addView(container, lp)
                attached = true
                hiding = false
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320L)
                    .setInterpolator(easeOut)
                    .start()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach system overlay: ${t.message}")
                attached = false
            }
        } else if (hiding) {
            hiding = false
            card.animate().cancel()
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(easeOut)
                .start()
        }
    }

    private fun hideOverlay(immediate: Boolean) {
        if (!attached) return
        val card = dockCard
        if (immediate || card == null) {
            removeWindowNow()
            return
        }
        if (hiding) return
        hiding = true
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .translationY(dp(34).toFloat())
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(400L)
            .setInterpolator(easeIn)
            .withEndAction {
                if (hiding) {
                    removeWindowNow()
                }
            }
            .start()
    }

    private fun removeWindowNow() {
        hiding = false
        val container = rootContainer
        dockCard?.animate()?.cancel()
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

    private fun postQuickCommand(urlStr: String) {
        thread(isDaemon = true) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 600
                    readTimeout = 600
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) {}
        }
    }
}
