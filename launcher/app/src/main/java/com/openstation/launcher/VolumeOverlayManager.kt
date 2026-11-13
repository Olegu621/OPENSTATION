package com.openstation.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating system-wide Liquid Glass volume OSD & status curtain rendered via
 * TYPE_APPLICATION_OVERLAY so it appears cleanly on top of all third-party apps
 * (YouTube, Kinopoisk, players) and the launcher itself.
 */
class VolumeOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "VolumeOverlayMgr"
        private const val AUTO_HIDE_VOL_MS = 1950L
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val easeIn = AccelerateInterpolator(1.3f)

    private var rootContainer: FrameLayout? = null
    private var cardLayout: LinearLayout? = null
    private var cardBg: GradientDrawable? = null
    private var iconView: SpeakerGlyphView? = null
    private var tvTitle: TextView? = null
    private var tvSubtitle: TextView? = null
    private var tvPercent: TextView? = null
    private var progressBarView: LiquidProgressView? = null

    private var attached = false
    private var hiding = false
    private var currentAnimPct = 0f
    private var targetPct = 0f
    private var currentMuted = false
    private var barAnimator: ValueAnimator? = null

    private val hideRunnable = Runnable {
        hideOverlay(immediate = false)
    }

    fun start() {
        // Overlay attaches lazily on first volume/status event
    }

    fun stop() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            barAnimator?.cancel()
            barAnimator = null
            removeWindowNow()
        }
    }

    fun showVolume(percent: Int, muted: Boolean) {
        val clamped = percent.coerceIn(0, 100)
        mainHandler.post {
            ensureViews()
            currentMuted = muted || clamped <= 0
            targetPct = if (muted) 0f else clamped.toFloat()

            tvTitle?.text = if (currentMuted) "ЗВУК ВЫКЛЮЧЕН" else "ГРОМКОСТЬ СТАНЦИИ"
            tvTitle?.setTextColor(Color.parseColor(if (currentMuted) "#FCA5A5" else "#94A3B8"))
            tvSubtitle?.visibility = View.GONE
            progressBarView?.visibility = View.VISIBLE
            tvPercent?.visibility = View.VISIBLE
            tvPercent?.text = if (currentMuted) "MUTE" else "$clamped%"
            tvPercent?.setTextColor(Color.parseColor(if (currentMuted) "#F87171" else "#F8FAFC"))

            iconView?.setLevel(clamped, currentMuted)
            progressBarView?.setMuted(currentMuted)

            val accent = if (currentMuted) Color.parseColor("#88F87171") else Color.parseColor("#7800D4FF")
            cardBg?.setStroke(dp(1), accent)

            animateProgressTo(targetPct)
            showWindowIfNeeded()

            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, AUTO_HIDE_VOL_MS)
        }
    }

    fun showToastPill(title: String, subtitle: String, accentHex: String = "#00D4FF", durationMs: Long = 2800L) {
        mainHandler.post {
            ensureViews()
            val accent = try {
                Color.parseColor(accentHex)
            } catch (_: Throwable) {
                Color.parseColor("#00D4FF")
            }
            val stroke = Color.argb(135, Color.red(accent), Color.green(accent), Color.blue(accent))
            cardBg?.setStroke(dp(1), stroke)

            iconView?.setBadgeMode(accent)
            tvTitle?.text = title.uppercase()
            tvTitle?.setTextColor(accent)
            tvSubtitle?.text = subtitle
            tvSubtitle?.visibility = if (subtitle.isNotBlank()) View.VISIBLE else View.GONE
            progressBarView?.visibility = View.GONE
            tvPercent?.visibility = View.GONE

            showWindowIfNeeded()
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, durationMs.coerceIn(1000L, 7000L))
        }
    }

    private fun animateProgressTo(target: Float) {
        barAnimator?.cancel()
        val start = currentAnimPct
        barAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 160L
            interpolator = easeOut
            addUpdateListener { va ->
                currentAnimPct = va.animatedValue as Float
                progressBarView?.setProgress(currentAnimPct)
            }
            start()
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    private fun ensureViews() {
        if (rootContainer != null) return

        val container = FrameLayout(context)
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#E610192D"),
                Color.parseColor("#EC080D18")
            )
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.parseColor("#7800D4FF"))
        }
        cardBg = bg

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = dp(18).toFloat()
            setPadding(dp(18), dp(12), dp(20), dp(12))
        }

        val icon = SpeakerGlyphView(context)
        card.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginEnd = dp(14)
        })

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(context).apply {
            text = "ГРОМКОСТЬ СТАНЦИИ"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pctTv = TextView(context).apply {
            text = "0%"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.END
            setPadding(dp(10), 0, 0, 0)
        }
        headerRow.addView(pctTv)

        col.addView(headerRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val subTv = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        col.addView(subTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        val prog = LiquidProgressView(context)
        col.addView(prog, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(10)
        ).apply { topMargin = dp(7) })

        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        container.addView(
            card,
            FrameLayout.LayoutParams(
                dp(390),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(22)
            }
        )

        rootContainer = container
        cardLayout = card
        iconView = icon
        tvTitle = titleTv
        tvSubtitle = subTv
        tvPercent = pctTv
        progressBarView = prog
    }

    private fun showWindowIfNeeded() {
        val container = rootContainer ?: return
        val card = cardLayout ?: return
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
                card.translationY = -dp(26).toFloat()
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
                    .setDuration(240L)
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
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(easeOut)
                .start()
        }
    }

    private fun hideOverlay(immediate: Boolean) {
        if (!attached) return
        val card = cardLayout
        if (immediate || card == null) {
            removeWindowNow()
            return
        }
        if (hiding) return
        hiding = true
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .translationY(-dp(22).toFloat())
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(280L)
            .setInterpolator(easeIn)
            .withEndAction {
                if (hiding) removeWindowNow()
            }
            .start()
    }

    private fun removeWindowNow() {
        hiding = false
        val container = rootContainer
        cardLayout?.animate()?.cancel()
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

    private class SpeakerGlyphView(context: Context) : View(context) {
        private var levelPct = 0
        private var muted = false
        private var badgeMode = false
        private var badgeColor = Color.parseColor("#00D4FF")

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val arcRect = RectF()
        private val spkPath = Path()

        fun setLevel(pct: Int, isMuted: Boolean) {
            badgeMode = false
            levelPct = pct.coerceIn(0, 100)
            muted = isMuted || pct <= 0
            invalidate()
        }

        fun setBadgeMode(color: Int) {
            badgeMode = true
            badgeColor = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            if (badgeMode) {
                paint.color = Color.argb(42, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor))
                canvas.drawCircle(w * 0.5f, h * 0.5f, min(w, h) * 0.45f, paint)
                arcPaint.color = badgeColor
                arcPaint.strokeWidth = w * 0.08f
                canvas.drawCircle(w * 0.5f, h * 0.5f, min(w, h) * 0.24f, arcPaint)
                return
            }

            paint.color = Color.parseColor(if (muted) "#F87171" else "#E2E8F0")
            spkPath.reset()
            spkPath.moveTo(w * 0.14f, h * 0.37f)
            spkPath.lineTo(w * 0.30f, h * 0.37f)
            spkPath.lineTo(w * 0.50f, h * 0.20f)
            spkPath.lineTo(w * 0.50f, h * 0.80f)
            spkPath.lineTo(w * 0.30f, h * 0.63f)
            spkPath.lineTo(w * 0.14f, h * 0.63f)
            spkPath.close()
            canvas.drawPath(spkPath, paint)

            arcPaint.strokeWidth = max(2f, w * 0.075f)
            if (muted) {
                arcPaint.color = Color.parseColor("#F87171")
                canvas.drawLine(w * 0.62f, h * 0.34f, w * 0.86f, h * 0.66f, arcPaint)
                canvas.drawLine(w * 0.86f, h * 0.34f, w * 0.62f, h * 0.66f, arcPaint)
                return
            }

            arcPaint.color = Color.parseColor("#00D4FF")
            if (levelPct >= 1) {
                arcRect.set(w * 0.44f, h * 0.34f, w * 0.68f, h * 0.66f)
                canvas.drawArc(arcRect, -48f, 96f, false, arcPaint)
            }
            if (levelPct >= 35) {
                arcRect.set(w * 0.42f, h * 0.23f, w * 0.80f, h * 0.77f)
                canvas.drawArc(arcRect, -50f, 100f, false, arcPaint)
            }
            if (levelPct >= 70) {
                arcPaint.color = Color.parseColor("#A855F7")
                arcRect.set(w * 0.40f, h * 0.12f, w * 0.92f, h * 0.88f)
                canvas.drawArc(arcRect, -52f, 104f, false, arcPaint)
            }
        }
    }

    private class LiquidProgressView(context: Context) : View(context) {
        private var progressPct = 0f
        private var muted = false
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3347648E")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val rect = RectF()
        private var lastW = 0f
        private var lastMuted = false

        fun setProgress(pct: Float) {
            progressPct = pct.coerceIn(0f, 100f)
            invalidate()
        }

        fun setMuted(m: Boolean) {
            muted = m
            lastW = -1f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = h * 0.5f

            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)

            val fillW = (w * (progressPct / 100f)).coerceIn(0f, w)
            if (fillW <= 1f) return

            if (lastW != w || lastMuted != muted) {
                lastW = w
                lastMuted = muted
                val c0 = Color.parseColor(if (muted) "#EF4444" else "#00D4FF")
                val c1 = Color.parseColor(if (muted) "#F87171" else "#A855F7")
                fillPaint.shader = LinearGradient(0f, 0f, w, 0f, c0, c1, Shader.TileMode.CLAMP)
            }
            rect.set(0f, 0f, max(h, fillW), h)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            if (!muted) {
                val hx = max(radius, fillW - radius * 0.5f)
                canvas.drawCircle(hx, h * 0.5f, radius * 0.72f, headPaint)
            }
        }
    }
}
