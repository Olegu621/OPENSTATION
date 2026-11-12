package com.openstation.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated full-screen or card weather overlay for TV Launcher.
 * Features live particle animation (Sun rays, drifting clouds, rain drops, snow, lightning),
 * bold temperature readout, condition text, and auto-dismiss after 15 seconds.
 */
class WeatherOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var isShowing = false

    // Particle background canvas
    private val particleCanvas = WeatherParticleCanvas(context)

    // UI elements
    private val cardContainer: LinearLayout
    private val tvCity: TextView
    private val tvTemp: TextView
    private val tvCondition: TextView
    private val tvHint: TextView

    init {
        visibility = GONE
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.parseColor("#E60A0D14")) // Semi-transparent frosted obsidian

        // Add animated canvas
        addView(particleCanvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Center card container
        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 36, 48, 36)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#33161B22"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#4D38BDF8"))
            }
            background = gd
        }

        val lpCard = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(cardContainer, lpCard)

        // City & Location
        tvCity = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        cardContainer.addView(tvCity)

        // Large Temperature
        tvTemp = TextView(context).apply {
            textSize = 84f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 6f, Color.parseColor("#6600D4FF"))
        }
        cardContainer.addView(tvTemp)

        // Condition text
        tvCondition = TextView(context).apply {
            textSize = 24f
            setTextColor(Color.parseColor("#E2E8F0"))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        cardContainer.addView(tvCondition)

        // Auto-dismiss hint
        tvHint = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            text = "Нажмите любую кнопку на пульте для закрытия"
            gravity = Gravity.CENTER
        }
        cardContainer.addView(tvHint)

        setOnClickListener { dismiss() }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun showWeather(temp: Int, condition: String, conditionText: String, city: String) {
        val sign = if (temp > 0) "+" else ""
        tvTemp.text = "$sign$temp°"
        tvCity.text = "📍 $city"
        tvCondition.text = conditionText.ifEmpty { "Ясно" }

        particleCanvas.setCondition(condition)

        visibility = VISIBLE
        isShowing = true
        alpha = 0f
        scaleX = 0.95f
        scaleY = 0.95f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350L)
            .setInterpolator(DecelerateInterpolator())
            // ViewPropertyAnimator keeps the listener set by dismiss(); clear it or the show animation hides the overlay
            .setListener(null)
            .start()

        particleCanvas.start()
        requestFocus()

        dismissRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { dismiss() }
        dismissRunnable = r
        handler.postDelayed(r, 15_000L) // 15 seconds
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(280L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isShowing) return
                    visibility = GONE
                    particleCanvas.stop()
                }
            })
            .start()
    }

    /**
     * Particle engine for sun rays, raindrops, snow flakes, clouds and lightning.
     */
    private class WeatherParticleCanvas(context: Context) : View(context) {
        private var condition = "sunny"
        private var running = false
        private var lastTimeMs = 0L

        private val pPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#4DFACC15")
        }

        private data class Particle(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var size: Float,
            var alpha: Float,
            var extra: Float = 0f
        )

        private val particles = Array(45) {
            Particle(0f, 0f, 0f, 0f, 2f, 0.5f)
        }

        init {
            resetParticles()
        }

        fun setCondition(cond: String) {
            condition = cond.lowercase()
            resetParticles()
            invalidate()
        }

        private fun resetParticles() {
            val w = if (width > 0) width.toFloat() else 1920f
            val h = if (height > 0) height.toFloat() else 1080f
            for (p in particles) {
                p.x = Random.nextFloat() * w
                p.y = Random.nextFloat() * h
                p.alpha = Random.nextFloat() * 0.7f + 0.3f
                when {
                    condition.contains("rain") || condition.contains("storm") -> {
                        p.vx = Random.nextFloat() * 2f - 4f
                        p.vy = Random.nextFloat() * 14f + 16f
                        p.size = Random.nextFloat() * 12f + 8f
                    }
                    condition.contains("snow") -> {
                        p.vx = Random.nextFloat() * 3f - 1.5f
                        p.vy = Random.nextFloat() * 2.5f + 1.5f
                        p.size = Random.nextFloat() * 5f + 3f
                    }
                    else -> { // sunny / clouds
                        p.vx = Random.nextFloat() * 0.8f + 0.2f
                        p.vy = Random.nextFloat() * 0.4f - 0.2f
                        p.size = Random.nextFloat() * 40f + 25f
                    }
                }
            }
        }

        fun start() {
            if (running) return
            running = true
            lastTimeMs = SystemClock.uptimeMillis()
            invalidate()
        }

        fun stop() {
            running = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            resetParticles()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val now = SystemClock.uptimeMillis()
            val dt = if (lastTimeMs == 0L) 0.016f else ((now - lastTimeMs) * 0.001f).coerceIn(0.005f, 0.05f)
            lastTimeMs = now

            if (condition.contains("sun") || condition.contains("clear")) {
                drawSunny(canvas, w, h, now)
            } else if (condition.contains("rain") || condition.contains("storm")) {
                drawRain(canvas, w, h, dt)
            } else if (condition.contains("snow")) {
                drawSnow(canvas, w, h, dt)
            } else {
                drawClouds(canvas, w, h, dt)
            }

            if (running) {
                postInvalidateOnAnimation()
            }
        }

        private fun drawSunny(canvas: Canvas, w: Float, h: Float, timeMs: Long) {
            val cx = w * 0.5f
            val cy = h * 0.32f
            val baseR = min(w, h) * 0.14f

            sunPaint.shader = RadialGradient(
                cx, cy, baseR * 1.8f,
                intArrayOf(Color.parseColor("#FFFACC15"), Color.parseColor("#66F59E0B"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, baseR * 1.8f, sunPaint)

            // Pulsing rotating rays
            val angleBase = (timeMs % 36000L) / 36000f * 360f
            for (i in 0 until 12) {
                val ang = Math.toRadians((angleBase + i * 30f).toDouble())
                val r1 = baseR * 1.15f
                val r2 = baseR * (1.45f + sin(timeMs * 0.003 + i) * 0.15f).toFloat()
                val x1 = cx + cos(ang).toFloat() * r1
                val y1 = cy + sin(ang).toFloat() * r1
                val x2 = cx + cos(ang).toFloat() * r2
                val y2 = cy + sin(ang).toFloat() * r2
                canvas.drawLine(x1, y1, x2, y2, rayPaint)
            }
        }

        private fun drawRain(canvas: Canvas, w: Float, h: Float, dt: Float) {
            val speedScale = (dt * 60f).coerceIn(0.5f, 2.0f)
            pPaint.color = Color.parseColor("#8038BDF8")
            pPaint.strokeWidth = 2.5f
            pPaint.style = Paint.Style.STROKE

            for (p in particles) {
                p.x += p.vx * speedScale
                p.y += p.vy * speedScale
                if (p.y > h) {
                    p.y = -20f
                    p.x = Random.nextFloat() * w
                }
                val tailY = p.y - p.size
                canvas.drawLine(p.x, tailY, p.x + p.vx * 0.5f, p.y, pPaint)
            }
        }

        private fun drawSnow(canvas: Canvas, w: Float, h: Float, dt: Float) {
            val speedScale = (dt * 60f).coerceIn(0.5f, 2.0f)
            pPaint.color = Color.WHITE
            pPaint.style = Paint.Style.FILL

            for (p in particles) {
                p.x += p.vx * speedScale
                p.y += p.vy * speedScale
                if (p.y > h) {
                    p.y = -10f
                    p.x = Random.nextFloat() * w
                }
                pPaint.alpha = (p.alpha * 255).toInt()
                canvas.drawCircle(p.x, p.y, p.size, pPaint)
            }
        }

        private fun drawClouds(canvas: Canvas, w: Float, h: Float, dt: Float) {
            val speedScale = (dt * 60f).coerceIn(0.5f, 2.0f)
            pPaint.color = Color.parseColor("#2694A3B8")
            pPaint.style = Paint.Style.FILL

            for (p in particles) {
                p.x += p.vx * speedScale
                if (p.x > w + 60f) {
                    p.x = -60f
                    p.y = Random.nextFloat() * h * 0.7f
                }
                canvas.drawCircle(p.x, p.y, p.size, pPaint)
            }
        }
    }
}
