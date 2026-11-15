package com.openstation.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure geometry & math helpers for UI card/spring calculations (JVM-testable).
 */
object SpatialGlassMath {
    fun smoothStep(cur: Float, target: Float, factor: Float): Float {
        val next = cur + (target - cur) * factor
        return if (abs(next - target) < 0.001f) target else next
    }

    /**
     * Converts Android top-left view rectangle into GL bottom-left center & half-size in pixels.
     */
    fun toGlLensRect(
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float,
        screenHeightPx: Float
    ): FloatArray {
        val w = max(0f, rightPx - leftPx)
        val h = max(0f, bottomPx - topPx)
        val cx = leftPx + w * 0.5f
        val cyAndroid = topPx + h * 0.5f
        val cyGl = screenHeightPx - cyAndroid
        return floatArrayOf(cx, cyGl, w * 0.5f, h * 0.5f)
    }

    fun computeSpringScale(focused: Boolean, pressed: Boolean): Float = when {
        pressed -> 0.965f
        focused -> 1.055f
        else -> 1.0f
    }
}

/**
 * Surface specification for frosted glass (or classic flat) UI elements registered with
 * [SpatialGlassBackdropView].
 */
data class SpatialLensSpec(
    val radiusDp: Float = 22f,
    val baseTint: Int = Color.parseColor("#2A0C1220"),
    val focusTint: Int = Color.parseColor("#42162238"),
    val accentTint: Int = Color.parseColor("#7DD3FC"),
    val bevelDp: Float = 14f,
    val refractDp: Float = 10f,
    val chromaDp: Float = 2.2f,
    val shadowDp: Float = 16f,
    val rimAlpha: Float = 0.28f,
    val focusRimAlpha: Float = 0.72f,
    val blurMix: Float = 0.88f,
    val clipParent: View? = null
)

data class GlassLensSnapshot(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
    val clipLeftPx: Float,
    val clipTopPx: Float,
    val clipRightPx: Float,
    val clipBottomPx: Float,
    val radiusPx: Float,
    val bevelPx: Float,
    val refractPx: Float,
    val chromaPx: Float,
    val shadowPx: Float,
    val alpha: Float,
    val focus: Float,
    val press: Float,
    val rimAlpha: Float,
    val focusRimAlpha: Float,
    val blurMix: Float,
    val baseTint: Int,
    val focusTint: Int,
    val accentTint: Int
)

/**
 * Locked-60FPS HWUI Frosted Glass & Classic Backdrop Compositor.
 *
 * Replaces multi-context OpenGL background distortion with native single-pipeline HWUI rendering:
 * - Zero TextureView GlLayer double-copy and zero BufferQueue stalls on Mali-G31.
 * - Zero optical background distortion/chromatic aberration shaders: clean creamy frosted glass in
 *   "Матовое стекло" mode, or razor-sharp v1.6.3 obsidian backdrop in "Классическая" mode.
 * - Zero idle CPU/GPU wakeups and zero per-frame OnPreDrawListener traversals.
 */
class SpatialGlassBackdropView(context: Context) : FrameLayout(context) {

    private data class BoundFrostedCard(
        val view: View,
        var spec: SpatialLensSpec,
        val drawable: FrostedGlassSurfaceDrawable
    )

    private val boundCards = ArrayList<BoundFrostedCard>(40)
    var onGlStatusListener: ((ready: Boolean, failed: Boolean) -> Unit)? = null
    @Volatile var motionRateScale: Float = MotionPrefs.glSmoothFactor(context)

    private val easeCurve = DecelerateInterpolator(2.1f)
    private val blurExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FrostedCoverBlur").apply { isDaemon = true }
    }
    private val coverGeneration = AtomicInteger(0)

    private var targetBackdropBlur = -1f
    private var targetBackdropDim = -1f
    private var targetOrbEnergy = -1f
    private var targetOrbHueTint = 0
    private var targetMusicCoverAlpha = -1f
    private var hasBlurredCover = false
    private var paused = false

    private val ivSharpBackdrop: ImageView
    private val ivCoverBackdrop: ImageView
    private val ivBlurBackdrop: ImageView
    private val classicScrimView: View
    private val dimScrimView: View
    private val orbAuraView: ImageView
    private var blurredCoverBmp: Bitmap? = null

    private val density: Float
        get() = resources.displayMetrics.density

    init {
        setWillNotDraw(true)

        ivSharpBackdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(ivSharpBackdrop)

        ivCoverBackdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            visibility = View.GONE
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(ivCoverBackdrop)

        ivBlurBackdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(ivBlurBackdrop)

        classicScrimView = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#54050811"),
                    Color.parseColor("#18050811"),
                    Color.parseColor("#78050811")
                )
            )
            visibility = if (LiquidGlassKit.isClassicUi()) View.VISIBLE else View.GONE
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(classicScrimView)

        dimScrimView = View(context).apply {
            setBackgroundColor(Color.parseColor("#FF03060E"))
            alpha = 0f
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(dimScrimView)

        orbAuraView = ImageView(context).apply {
            setImageBitmap(createOrbAuraBitmap())
            scaleType = ImageView.ScaleType.CENTER
            alpha = 0f
            visibility = View.GONE
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(orbAuraView)

        LiquidGlassKit.sharpWallpaper?.let { setWallpaperBitmap(it) }
        post {
            onGlStatusListener?.invoke(true, false)
        }
    }

    private fun animDurationMs(baseMs: Long): Long =
        max(80L, (baseMs * (1f / motionRateScale.coerceIn(0.45f, 2.5f))).roundToInt().toLong())

    fun setWallpaperBitmap(bmp: Bitmap?) {
        if (bmp != null && !bmp.isRecycled) {
            ivSharpBackdrop.setImageBitmap(bmp)
        }
        val blurred = LiquidGlassKit.blurredWallpaper
        if (blurred != null && !blurred.isRecycled) {
            ivBlurBackdrop.setImageBitmap(blurred)
        }
        refreshThemeMode()
    }

    fun setMusicCoverBitmap(bmp: Bitmap?, showAmbient: Boolean) {
        val gen = coverGeneration.incrementAndGet()
        if (bmp == null || bmp.isRecycled || !showAmbient) {
            hasBlurredCover = false
            targetMusicCoverAlpha = 0f
            if (!paused) {
                ivCoverBackdrop.animate().cancel()
                ivCoverBackdrop.animate()
                    .alpha(0f)
                    .setDuration(animDurationMs(260L))
                    .setInterpolator(easeCurve)
                    .withEndAction {
                        if (!hasBlurredCover) {
                            ivCoverBackdrop.setImageDrawable(null)
                            ivCoverBackdrop.visibility = View.GONE
                        }
                    }
                    .start()
            } else {
                ivCoverBackdrop.alpha = 0f
                ivCoverBackdrop.visibility = View.GONE
            }
            return
        }

        blurExecutor.execute {
            val blurred = LiquidGlassKit.blurBitmapFast(bmp, maxDim = 220, radius = 14) ?: return@execute
            if (coverGeneration.get() != gen) {
                blurred.recycle()
                return@execute
            }
            post {
                if (coverGeneration.get() != gen) {
                    blurred.recycle()
                    return@post
                }
                val old = blurredCoverBmp
                blurredCoverBmp = blurred
                ivCoverBackdrop.setImageBitmap(blurred)
                if (old != null && old !== blurred && !old.isRecycled) {
                    old.recycle()
                }
                hasBlurredCover = true
                ivCoverBackdrop.visibility = View.VISIBLE
                val dstAlpha = if (LiquidGlassKit.isClassicUi()) 0.42f else 0.72f
                targetMusicCoverAlpha = dstAlpha
                if (paused) {
                    ivCoverBackdrop.alpha = dstAlpha
                } else {
                    ivCoverBackdrop.animate().cancel()
                    ivCoverBackdrop.animate()
                        .alpha(dstAlpha)
                        .setDuration(animDurationMs(320L))
                        .setInterpolator(easeCurve)
                        .start()
                }
            }
        }
    }

    fun setSceneState(
        backdropBlur: Float = targetBackdropBlur.coerceAtLeast(0f),
        backdropDim: Float = targetBackdropDim.coerceAtLeast(0f),
        orbEnergy: Float = targetOrbEnergy.coerceAtLeast(0f),
        orbColor: Int = if (targetOrbHueTint != 0) targetOrbHueTint else Color.parseColor("#38BDF8"),
        musicAmbientAlpha: Float = targetMusicCoverAlpha.coerceAtLeast(0f)
    ) {
        val classic = LiquidGlassKit.isClassicUi()
        val clampedBlur = if (classic) 0f else backdropBlur.coerceIn(0f, 1f)
        val clampedDim = backdropDim.coerceIn(0f, 0.85f)
        val clampedOrb = orbEnergy.coerceIn(0f, 1.5f)
        val clampedMus = if (hasBlurredCover) musicAmbientAlpha.coerceIn(0f, if (classic) 0.48f else 0.86f) else 0f

        val dur = animDurationMs(320L)

        if (abs(clampedBlur - targetBackdropBlur) > 0.005f) {
            targetBackdropBlur = clampedBlur
            ivBlurBackdrop.animate().cancel()
            if (classic) {
                ivBlurBackdrop.alpha = 0f
                ivBlurBackdrop.visibility = View.GONE
            } else {
                ivBlurBackdrop.visibility = View.VISIBLE
                if (paused) {
                    ivBlurBackdrop.alpha = clampedBlur
                } else {
                    ivBlurBackdrop.animate().alpha(clampedBlur).setDuration(dur).setInterpolator(easeCurve).start()
                }
            }
        }

        if (abs(clampedDim - targetBackdropDim) > 0.005f) {
            targetBackdropDim = clampedDim
            dimScrimView.animate().cancel()
            if (paused) {
                dimScrimView.alpha = clampedDim
            } else {
                dimScrimView.animate().alpha(clampedDim).setDuration(dur).setInterpolator(easeCurve).start()
            }
        }

        if (hasBlurredCover && abs(clampedMus - targetMusicCoverAlpha) > 0.005f) {
            targetMusicCoverAlpha = clampedMus
            ivCoverBackdrop.animate().cancel()
            ivCoverBackdrop.visibility = if (clampedMus > 0.01f) View.VISIBLE else View.GONE
            if (paused) {
                ivCoverBackdrop.alpha = clampedMus
            } else {
                ivCoverBackdrop.animate().alpha(clampedMus).setDuration(dur).setInterpolator(easeCurve).start()
            }
        }

        val auraAlpha = ((clampedOrb - 0.16f).coerceIn(0f, 1f)) * 0.28f
        if (orbColor != targetOrbHueTint) {
            targetOrbHueTint = orbColor
            orbAuraView.colorFilter = PorterDuffColorFilter(orbColor, PorterDuff.Mode.SRC_ATOP)
        }
        if (abs(clampedOrb - targetOrbEnergy) > 0.03f) {
            targetOrbEnergy = clampedOrb
            orbAuraView.animate().cancel()
            if (auraAlpha <= 0.01f) {
                orbAuraView.alpha = 0f
                orbAuraView.visibility = View.GONE
            } else {
                orbAuraView.visibility = View.VISIBLE
                if (paused) {
                    orbAuraView.alpha = auraAlpha
                } else {
                    orbAuraView.animate().alpha(auraAlpha).setDuration(dur).setInterpolator(easeCurve).start()
                }
            }
        }
    }

    fun refreshThemeMode() {
        val classic = LiquidGlassKit.isClassicUi()
        classicScrimView.visibility = if (classic) View.VISIBLE else View.GONE
        if (classic) {
            ivBlurBackdrop.animate().cancel()
            ivBlurBackdrop.alpha = 0f
            ivBlurBackdrop.visibility = View.GONE
        } else {
            ivBlurBackdrop.visibility = View.VISIBLE
            ivBlurBackdrop.alpha = targetBackdropBlur.coerceIn(0f, 1f)
        }
        for (i in boundCards.indices) {
            boundCards[i].drawable.invalidateThemeStyle()
            boundCards[i].view.invalidate()
        }
    }

    fun registerLens(view: View, spec: SpatialLensSpec = SpatialLensSpec()): () -> Unit {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.defaultFocusHighlightEnabled = false
        }
        val existing = boundCards.firstOrNull { it.view === view }
        if (existing != null) {
            existing.spec = spec
            existing.drawable.updateSpec(spec)
            if (view.background !== existing.drawable) {
                view.background = existing.drawable
            }
        } else {
            val dr = FrostedGlassSurfaceDrawable(density, spec)
            boundCards.add(BoundFrostedCard(view, spec, dr))
            view.background = dr
        }
        return {
            boundCards.removeAll { it.view === view }
        }
    }

    fun updateLensSpec(view: View, updater: (SpatialLensSpec) -> SpatialLensSpec) {
        val entry = boundCards.firstOrNull { it.view === view } ?: return
        val next = updater(entry.spec)
        if (next != entry.spec) {
            entry.spec = next
            entry.drawable.updateSpec(next)
            view.invalidate()
        }
    }

    fun requestRenderFrame() = Unit

    fun syncLensesBeforeDraw(waitForFrame: Boolean = false) = Unit

    fun onHostPause() {
        paused = true
        ivBlurBackdrop.animate().cancel()
        ivCoverBackdrop.animate().cancel()
        dimScrimView.animate().cancel()
        orbAuraView.animate().cancel()
    }

    fun onHostResume() {
        paused = false
        refreshThemeMode()
    }

    fun releaseGl() {
        paused = true
        coverGeneration.incrementAndGet()
        blurExecutor.shutdownNow()
        blurredCoverBmp?.let { if (!it.isRecycled) it.recycle() }
        blurredCoverBmp = null
    }

    private fun createOrbAuraBitmap(): Bitmap {
        val sz = 160
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            sz * 0.5f, sz * 0.5f, sz * 0.5f,
            intArrayOf(
                Color.argb(180, 255, 255, 255),
                Color.argb(65, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(sz * 0.5f, sz * 0.5f, sz * 0.5f, p)
        return bmp
    }

    /**
     * Zero-distortion native HWUI Frosted Glass & Classic Flat Obsidian Drawable.
     * Caches all shaders per view size and state so steady-state & scroll draws allocate zero objects.
     */
    private class FrostedGlassSurfaceDrawable(
        private val density: Float,
        private var spec: SpatialLensSpec
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rectF = RectF()
        private val rimRectF = RectF()

        private var isFocusedState = false
        private var isPressedState = false
        private var cachedW = -1f
        private var cachedH = -1f
        private var cachedStateKey = -1
        private var cachedClassic = LiquidGlassKit.isClassicUi()

        fun updateSpec(next: SpatialLensSpec) {
            spec = next
            cachedStateKey = -1
            invalidateSelf()
        }

        fun invalidateThemeStyle() {
            cachedClassic = LiquidGlassKit.isClassicUi()
            cachedStateKey = -1
            invalidateSelf()
        }

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            var foc = false
            var prs = false
            for (s in state) {
                if (s == android.R.attr.state_focused || s == android.R.attr.state_selected) foc = true
                if (s == android.R.attr.state_pressed) prs = true
            }
            if (foc != isFocusedState || prs != isPressedState) {
                isFocusedState = foc
                isPressedState = prs
                cachedStateKey = -1
                invalidateSelf()
                return true
            }
            return false
        }

        private fun rebuildPaintsIfNeeded(w: Float, h: Float) {
            val classic = LiquidGlassKit.isClassicUi()
            val key = (if (isFocusedState) 2 else 0) or (if (isPressedState) 1 else 0)
            if (w == cachedW && h == cachedH && key == cachedStateKey && classic == cachedClassic) return
            cachedW = w
            cachedH = h
            cachedStateKey = key
            cachedClassic = classic

            if (classic) {
                val base = normalizeClassicFill(spec.baseTint, focused = false)
                val foc = normalizeClassicFill(spec.focusTint, focused = true)
                val bg = when {
                    isPressedState -> LiquidGlassKit.blendColors(foc, Color.WHITE, 0.14f)
                    isFocusedState -> foc
                    else -> base
                }
                fillPaint.shader = null
                fillPaint.color = bg
                sheenPaint.shader = null
                sheenPaint.color = Color.TRANSPARENT

                val hasAccent = spec.accentTint != 0 && Color.alpha(spec.accentTint) > 10
                val strokeCol = when {
                    isFocusedState || isPressedState ->
                        if (hasAccent) spec.accentTint else Color.parseColor("#D838BDF8")
                    hasAccent -> LiquidGlassKit.withAlpha(spec.accentTint, 165)
                    else -> Color.parseColor("#38FFFFFF")
                }
                rimPaint.shader = null
                rimPaint.color = strokeCol
                rimPaint.strokeWidth = max(1f, (if (isFocusedState || isPressedState) 1.8f else 1.0f) * density)
            } else {
                // Smooth translucent smoked frosted glass fill (NO distortion, high contrast & creamy glow)
                val baseTop = normalizeFrostedFill(spec.baseTint, top = true)
                val baseBot = normalizeFrostedFill(spec.baseTint, top = false)
                val focTop = normalizeFrostedFill(spec.focusTint, top = true)
                val focBot = normalizeFrostedFill(spec.focusTint, top = false)

                val topCol = when {
                    isPressedState -> LiquidGlassKit.blendColors(focTop, Color.WHITE, 0.16f)
                    isFocusedState -> focTop
                    else -> baseTop
                }
                val botCol = when {
                    isPressedState -> LiquidGlassKit.blendColors(focBot, Color.WHITE, 0.08f)
                    isFocusedState -> focBot
                    else -> baseBot
                }
                fillPaint.shader = LinearGradient(0f, 0f, w * 0.25f, h, topCol, botCol, Shader.TileMode.CLAMP)

                val sheenTopAlpha = if (isFocusedState || isPressedState) 44 else 24
                sheenPaint.shader = LinearGradient(
                    0f, 0f, 0f, h * 0.58f,
                    intArrayOf(
                        Color.argb(sheenTopAlpha, 255, 255, 255),
                        Color.argb(sheenTopAlpha / 4, 224, 242, 254),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 1f),
                    Shader.TileMode.CLAMP
                )

                val hasAccent = spec.accentTint != 0 && Color.alpha(spec.accentTint) > 10
                val rimA = if (isFocusedState || isPressedState) spec.focusRimAlpha else spec.rimAlpha
                val strokeAlphaHi = (rimA * 255f).roundToInt().coerceIn(38, 235)
                val strokeAlphaLo = (strokeAlphaHi * 0.26f).roundToInt().coerceIn(12, 90)
                val hiColor = when {
                    hasAccent -> LiquidGlassKit.withAlpha(spec.accentTint, max(strokeAlphaHi, 185))
                    isFocusedState -> Color.argb(strokeAlphaHi, 186, 230, 253)
                    else -> Color.argb(strokeAlphaHi, 255, 255, 255)
                }
                val loColor = Color.argb(strokeAlphaLo, 255, 255, 255)
                rimPaint.strokeWidth = max(1f, (if (isFocusedState || isPressedState) 1.85f else 1.1f) * density)
                rimPaint.shader = LinearGradient(0f, 0f, w * 0.85f, h, hiColor, loColor, Shader.TileMode.CLAMP)
            }
        }

        private fun normalizeFrostedFill(tint: Int, top: Boolean): Int {
            val r = Color.red(tint)
            val g = Color.green(tint)
            val b = Color.blue(tint)
            val srcA = Color.alpha(tint)
            // Lift overly-transparent GL tints into rich frosted matte density cleanly
            val frostedA = max(srcA, if (top) 168 else 188).coerceIn(140, 228)
            val boost = if (top) 1.24f else 0.92f
            return Color.argb(
                frostedA,
                min(255, (r * boost + if (top) 10 else 2).toInt()),
                min(255, (g * boost + if (top) 14 else 4).toInt()),
                min(255, (b * boost + if (top) 20 else 6).toInt())
            )
        }

        private fun normalizeClassicFill(tint: Int, focused: Boolean): Int {
            val r = Color.red(tint)
            val g = Color.green(tint)
            val b = Color.blue(tint)
            val a = if (focused) 226 else max(Color.alpha(tint), 196).coerceAtMost(218)
            return Color.argb(a, r, g, b)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 2f || h <= 2f) return
            rebuildPaintsIfNeeded(w, h)

            val rad = min(spec.radiusDp * density, min(w, h) * 0.5f)
            val saved = canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            rectF.set(0f, 0f, w, h)
            canvas.drawRoundRect(rectF, rad, rad, fillPaint)
            if (!cachedClassic) {
                canvas.drawRoundRect(rectF, rad, rad, sheenPaint)
            }
            val inset = rimPaint.strokeWidth * 0.5f
            rimRectF.set(inset, inset, max(inset + 1f, w - inset), max(inset + 1f, h - inset))
            canvas.drawRoundRect(rimRectF, max(1f, rad - inset), max(1f, rad - inset), rimPaint)
            canvas.restoreToCount(saved)
        }

        override fun getOutline(outline: Outline) {
            val b = bounds
            if (b.isEmpty) return
            val rad = min(spec.radiusDp * density, min(b.width().toFloat(), b.height().toFloat()) * 0.5f)
            outline.setRoundRect(b, rad)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            rimPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
