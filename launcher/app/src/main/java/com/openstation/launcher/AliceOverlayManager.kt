package com.openstation.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
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
    private var cardBg: GradientDrawable? = null
    private var orbView: AliceOrbView? = null
    private var stateDot: View? = null
    private var stateDotBg: GradientDrawable? = null
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

    fun start() {
        mainHandler.post {
            LiveOrbState.addListener(stateListener)
            evaluateVisibility()
        }
    }

    fun stop() {
        mainHandler.post {
            LiveOrbState.removeListener(stateListener)
            mainHandler.removeCallbacks(dismissRunnable)
            dismissScheduled = false
            removeWindowNow()
        }
    }

    fun setLauncherForeground(fg: Boolean) {
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

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun ensureViews() {
        if (rootContainer != null) return

        val container = FrameLayout(context)
        val cardShape = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.parseColor("#EB090E1A"),
                Color.parseColor("#EE10192D"),
                Color.parseColor("#EB0A1120")
            )
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.parseColor("#7AE8C686"))
        }
        cardBg = cardShape

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardShape
            elevation = dp(14).toFloat()
            setPadding(dp(12), dp(8), dp(20), dp(8))
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
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                marginEnd = dp(14)
            }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dotShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#38BDF8"))
        }
        stateDotBg = dotShape
        val dot = View(context).apply {
            background = dotShape
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(7)
            }
        }

        val stateTv = TextView(context).apply {
            text = "АЛИСА · СЛУШАЮ"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        val userTv = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }

        topRow.addView(dot)
        topRow.addView(stateTv)
        topRow.addView(userTv)

        val replyTv = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }

        textCol.addView(topRow)
        textCol.addView(replyTv)

        card.addView(orb)
        card.addView(textCol)

        container.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(76),
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(42)
                rightMargin = dp(42)
                bottomMargin = dp(16)
            }
        )

        rootContainer = container
        dockCard = card
        orbView = orb
        stateDot = dot
        tvState = stateTv
        tvUserHint = userTv
        tvReply = replyTv
    }

    private fun bindState() {
        val st = LiveOrbState.assistantState
        val userTxt = LiveOrbState.liveUserText.trim()
        val display = LiveOrbState.bestDisplayText()

        val accent: Int
        val borderColor: Int
        val stateLabel: String
        val fallbackReply: String

        when (st) {
            "connecting" -> {
                accent = Color.parseColor("#38BDF8")
                borderColor = Color.parseColor("#8838BDF8")
                stateLabel = "АЛИСА · ПОДКЛЮЧЕНИЕ"
                fallbackReply = "Подключаю голосовой поток..."
            }
            "listening" -> {
                accent = Color.parseColor("#2DD4BF")
                borderColor = Color.parseColor("#992DD4BF")
                stateLabel = "АЛИСА · СЛУШАЮ"
                fallbackReply = if (userTxt.isNotEmpty()) "«$userTxt»" else "Говорите, я внимательно слушаю..."
            }
            "thinking" -> {
                accent = Color.parseColor("#E8C686")
                borderColor = Color.parseColor("#AAE8C686")
                stateLabel = "АЛИСА · ДУМАЮ"
                fallbackReply = if (userTxt.isNotEmpty()) "Обрабатываю: «$userTxt»" else "Формирую ответ..."
            }
            "speaking" -> {
                accent = Color.parseColor("#C084FC")
                borderColor = Color.parseColor("#AAC084FC")
                stateLabel = "АЛИСА · ОТВЕЧАЮ"
                fallbackReply = "Отвечаю голосом..."
            }
            "error" -> {
                accent = Color.parseColor("#F87171")
                borderColor = Color.parseColor("#AAF87171")
                stateLabel = "АЛИСА · ОШИБКА"
                fallbackReply = "Не удалось выполнить запрос"
            }
            else -> {
                accent = Color.parseColor("#E8C686")
                borderColor = Color.parseColor("#7AE8C686")
                stateLabel = "АЛИСА"
                fallbackReply = ""
            }
        }

        stateDotBg?.setColor(accent)
        cardBg?.setStroke(dp(1), borderColor)
        tvState?.text = stateLabel
        tvState?.setTextColor(accent)

        if (userTxt.isNotEmpty() && display != "«$userTxt»") {
            tvUserHint?.text = "Вы: «$userTxt»"
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
                dp(108),
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
