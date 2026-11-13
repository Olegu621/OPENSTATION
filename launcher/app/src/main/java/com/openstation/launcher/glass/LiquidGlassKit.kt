package com.openstation.launcher.glass

import com.openstation.launcher.*
import com.openstation.launcher.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optical Liquid Glass & Curated Wallpaper Engine for Android 9 (API 28).
 *
 * Precomputes sharp & blurred wallpaper bitmaps per theme and renders views with
 * genuine window-aligned optical bevel refraction via Canvas.drawBitmapMesh,
 * specular caustic sheen, and double prism rims.
 */
object LiquidGlassKit {

    data class WallpaperTheme(
        val id: String,
        val title: String,
        val subtitle: String,
        val baseDarkHex: String,
        val primaryHex: String,
        val secondaryHex: String,
        val highlightHex: String,
        val useStagePhotoTint: Boolean = false
    )

    val THEMES: List<WallpaperTheme> = listOf(
        WallpaperTheme(
            id = "obsidian_gold",
            title = "Титан и обсидиан",
            subtitle = "Глубокий OLED",
            baseDarkHex = "#030509",
            primaryHex = "#94A3B8",
            secondaryHex = "#3B82F6",
            highlightHex = "#E2E8F0",
            useStagePhotoTint = false
        ),
        WallpaperTheme(
            id = "abyssal_cyan",
            title = "Арктический лёд",
            subtitle = "Холодный сапфир",
            baseDarkHex = "#020710",
            primaryHex = "#06B6D4",
            secondaryHex = "#2563EB",
            highlightHex = "#E0F2FE"
        ),
        WallpaperTheme(
            id = "nebula_violet",
            title = "Глубокий космос",
            subtitle = "Ультрамарин и туман",
            baseDarkHex = "#05040E",
            primaryHex = "#8B5CF6",
            secondaryHex = "#38BDF8",
            highlightHex = "#EDE9FE"
        ),
        WallpaperTheme(
            id = "liquid_mercury",
            title = "Жидкая платина",
            subtitle = "Серый монолит",
            baseDarkHex = "#05070B",
            primaryHex = "#CBD5E1",
            secondaryHex = "#64748B",
            highlightHex = "#F8FAFC"
        ),
        WallpaperTheme(
            id = "solar_amber",
            title = "Тёплый графит",
            subtitle = "Приглушённая бронза",
            baseDarkHex = "#0B0603",
            primaryHex = "#D4AF6A",
            secondaryHex = "#9A3412",
            highlightHex = "#FEF3C7"
        ),
        WallpaperTheme(
            id = "deep_emerald",
            title = "Северное сияние",
            subtitle = "Малахит и ночь",
            baseDarkHex = "#020A08",
            primaryHex = "#10B981",
            secondaryHex = "#0284C7",
            highlightHex = "#D1FAE5"
        )
    )

    private const val PREFS_UI = "openstation_ui"
    private const val KEY_WALLPAPER_ID = "wallpaper_theme_id"

    @Volatile
    var currentThemeId: String = "obsidian_gold"
        private set

    @Volatile
    var wallpaperVersion: Int = 0
        private set

    @Volatile
    var sharpWallpaper: Bitmap? = null
        private set

    @Volatile
    var blurredWallpaper: Bitmap? = null
        private set

    private var stagePhotoCache: Bitmap? = null

    fun currentTheme(): WallpaperTheme =
        THEMES.firstOrNull { it.id == currentThemeId } ?: THEMES.first()

    fun loadSavedThemeId(ctx: Context): String {
        val saved = ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .getString(KEY_WALLPAPER_ID, "obsidian_gold") ?: "obsidian_gold"
        return THEMES.firstOrNull { it.id == saved }?.id ?: "obsidian_gold"
    }

    @Synchronized
    fun ensureInitialized(ctx: Context): WallpaperTheme {
        val saved = loadSavedThemeId(ctx)
        if (sharpWallpaper == null || blurredWallpaper == null || currentThemeId != saved) {
            setWallpaper(ctx, saved, persist = false)
        }
        return currentTheme()
    }

    @Synchronized
    fun setWallpaper(ctx: Context, themeId: String, persist: Boolean = true): WallpaperTheme {
        val theme = THEMES.firstOrNull { it.id == themeId } ?: THEMES.first()
        currentThemeId = theme.id
        if (persist) {
            ctx.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WALLPAPER_ID, theme.id)
                .apply()
        }
        val (sharp, blurred) = renderThemePair(ctx, theme)
        sharpWallpaper = sharp
        blurredWallpaper = blurred
        wallpaperVersion++
        return theme
    }

    private fun getStagePhotoScaled(ctx: Context, w: Int, h: Int): Bitmap? {
        stagePhotoCache?.let { cached ->
            if (!cached.isRecycled && cached.width == w && cached.height == h) return cached
        }
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val raw = BitmapFactory.decodeResource(ctx.resources, R.drawable.bg_tv_stage, opts) ?: return null
            val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
            if (scaled !== raw) raw.recycle()
            stagePhotoCache = scaled
            scaled
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderThemePair(ctx: Context, theme: WallpaperTheme): Pair<Bitmap, Bitmap> {
        val w = 960
        val h = 540
        val sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sharp)

        val base = Color.parseColor(theme.baseDarkHex)
        val c1 = Color.parseColor(theme.primaryHex)
        val c2 = Color.parseColor(theme.secondaryHex)
        val c3 = Color.parseColor(theme.highlightHex)

        c.drawColor(base)

        // Silky OLED spatial studio ambient blooms (zero wireframe lines, zero brown photo wash)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        p.shader = RadialGradient(
            w * 0.22f, h * 0.20f, w * 0.62f,
            intArrayOf(withAlpha(c2, 68), withAlpha(c2, 20), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(w * 0.22f, h * 0.20f, w * 0.62f, p)

        p.shader = RadialGradient(
            w * 0.80f, h * 0.78f, w * 0.60f,
            intArrayOf(withAlpha(c1, 62), withAlpha(c1, 18), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(w * 0.80f, h * 0.78f, w * 0.60f, p)

        p.shader = RadialGradient(
            w * 0.50f, h * 0.40f, w * 0.44f,
            intArrayOf(withAlpha(c3, 30), withAlpha(c2, 10), Color.TRANSPARENT),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(w * 0.50f, h * 0.40f, w * 0.44f, p)

        // Feather vignette for deep cinema OLED contrast
        p.shader = RadialGradient(
            w * 0.5f, h * 0.46f, w * 0.74f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#44020408"), Color.parseColor("#AA020408")),
            floatArrayOf(0.42f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // Downscaled pre-blurred version for ultra-smooth GPU glass refraction & modal scrims
        val bw = 240
        val bh = 135
        val small = Bitmap.createScaledBitmap(sharp, bw, bh, true)
        fastBoxBlurInPlace(small, radius = 10, passes = 2)
        return sharp to small
    }

    private fun fastBoxBlurInPlace(bmp: Bitmap, radius: Int, passes: Int) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        val tmp = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1
        repeat(passes) {
            // Horizontal pass
            for (y in 0 until h) {
                val rowOff = y * w
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dx in -radius..radius) {
                    val c = pixels[rowOff + dx.coerceIn(0, w - 1)]
                    rSum += (c ushr 16) and 0xFF
                    gSum += (c ushr 8) and 0xFF
                    bSum += c and 0xFF
                }
                for (x in 0 until w) {
                    tmp[rowOff + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                    val addIdx = rowOff + (x + radius + 1).coerceAtMost(w - 1)
                    val remIdx = rowOff + (x - radius).coerceAtLeast(0)
                    val cAdd = pixels[addIdx]
                    val cRem = pixels[remIdx]
                    rSum += ((cAdd ushr 16) and 0xFF) - ((cRem ushr 16) and 0xFF)
                    gSum += ((cAdd ushr 8) and 0xFF) - ((cRem ushr 8) and 0xFF)
                    bSum += (cAdd and 0xFF) - (cRem and 0xFF)
                }
            }
            // Vertical pass
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in -radius..radius) {
                    val c = tmp[dy.coerceIn(0, h - 1) * w + x]
                    rSum += (c ushr 16) and 0xFF
                    gSum += (c ushr 8) and 0xFF
                    bSum += c and 0xFF
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                    val addIdx = (y + radius + 1).coerceAtMost(h - 1) * w + x
                    val remIdx = (y - radius).coerceAtLeast(0) * w + x
                    val cAdd = tmp[addIdx]
                    val cRem = tmp[remIdx]
                    rSum += ((cAdd ushr 16) and 0xFF) - ((cRem ushr 16) and 0xFF)
                    gSum += ((cAdd ushr 8) and 0xFF) - ((cRem ushr 8) and 0xFF)
                    bSum += (cAdd and 0xFF) - (cRem and 0xFF)
                }
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun blendColors(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    /**
     * Optical Liquid Glass Drawable:
     * Renders true window-aligned blurred wallpaper refraction using a GPU-accelerated
     * dual-scale BitmapShader optical bevel (1.03x interior + 1.16x refracted perimeter edge),
     * overlaid with frosted obsidian tint, diagonal caustic sheen, inner crystalline rim,
     * and focus-reactive prism stroke.
     */
    class RefractiveGlassDrawable(
        private val anchorView: View,
        private val radiusPx: Float,
        private val baseTint: Int = Color.parseColor("#540A1222"),
        private val focusTint: Int = Color.parseColor("#7315253E"),
        private var normalStroke: Int = Color.parseColor("#38FFFFFF"),
        private var focusStroke: Int = Color.parseColor("#FFE8C686"),
        private val strokeWidthPx: Float = 2.2f,
        private val focusStrokeWidthPx: Float = 4.2f,
        private val refractStrength: Float = 1.0f,
        private val glassAlpha: Int = 225
    ) : Drawable() {

        private val loc = IntArray(2)
        private val clipPath = Path()
        private val innerRimPath = Path()
        private val bevelPath = Path()
        private val rectF = RectF()
        private val innerRectF = RectF()
        private val bevelRectF = RectF()

        private val interiorMatrix = android.graphics.Matrix()
        private val bevelMatrix = android.graphics.Matrix()
        private var cachedShaderBitmap: Bitmap? = null
        private var interiorShader: android.graphics.BitmapShader? = null
        private var bevelShader: android.graphics.BitmapShader? = null

        private val interiorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        private var isFocusedState = false
        private var isPressedState = false
        private var cachedW = -1f
        private var cachedH = -1f
        private var cachedThemeVer = -1
        private var cachedStateKey = -1

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

        private fun rebuildShadersIfNeeded(w: Float, h: Float) {
            val stateKey = (if (isFocusedState) 2 else 0) or (if (isPressedState) 1 else 0)
            if (w == cachedW && h == cachedH && wallpaperVersion == cachedThemeVer && stateKey == cachedStateKey) {
                return
            }
            cachedW = w
            cachedH = h
            cachedThemeVer = wallpaperVersion
            cachedStateKey = stateKey

            rectF.set(0f, 0f, w, h)
            val rad = min(radiusPx, min(w, h) * 0.5f)
            clipPath.reset()
            clipPath.addRoundRect(rectF, rad, rad, Path.Direction.CW)

            val pad = max(1.5f, strokeWidthPx * 0.9f)
            innerRectF.set(pad, pad, max(pad + 1f, w - pad), max(pad + 1f, h - pad))
            innerRimPath.reset()
            innerRimPath.addRoundRect(innerRectF, max(1f, rad - pad), max(1f, rad - pad), Path.Direction.CW)

            val bevelWidth = max(6f, min(w, h) * 0.14f).coerceAtMost(22f) * refractStrength
            bevelPaint.strokeWidth = bevelWidth
            val bHalf = bevelWidth * 0.45f
            bevelRectF.set(bHalf, bHalf, max(bHalf + 1f, w - bHalf), max(bHalf + 1f, h - bHalf))
            bevelPath.reset()
            bevelPath.addRoundRect(bevelRectF, max(1f, rad - bHalf * 0.5f), max(1f, rad - bHalf * 0.5f), Path.Direction.CW)

            val theme = currentTheme()
            val accent = Color.parseColor(theme.primaryHex)
            val accent2 = Color.parseColor(theme.secondaryHex)

            val fillTop = when {
                isPressedState -> blendColors(focusTint, Color.WHITE, 0.20f)
                isFocusedState -> blendColors(focusTint, Color.WHITE, 0.12f)
                else -> baseTint
            }
            val fillBot = when {
                isFocusedState -> Color.argb(min(255, Color.alpha(fillTop) + 22), Color.red(fillTop), Color.green(fillTop), Color.blue(fillTop))
                else -> Color.argb(min(255, Color.alpha(baseTint) + 18), Color.red(baseTint), Color.green(baseTint), Color.blue(baseTint))
            }
            tintPaint.shader = LinearGradient(0f, 0f, w * 0.35f, h, fillTop, fillBot, Shader.TileMode.CLAMP)

            val sheenAlpha = if (isFocusedState) 74 else 24
            causticPaint.shader = LinearGradient(
                0f, 0f, w * 0.85f, h * 0.85f,
                intArrayOf(
                    Color.argb(sheenAlpha, 255, 255, 255),
                    Color.argb(sheenAlpha / 3, Color.red(accent2), Color.green(accent2), Color.blue(accent2)),
                    Color.TRANSPARENT,
                    Color.argb(if (isFocusedState) 28 else 10, Color.red(accent), Color.green(accent), Color.blue(accent))
                ),
                floatArrayOf(0f, 0.28f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )

            innerRimPaint.strokeWidth = max(1.0f, strokeWidthPx * 0.85f)
            innerRimPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.argb(if (isFocusedState) 170 else 64, 255, 255, 255),
                    Color.argb(14, 255, 255, 255),
                    Color.argb(if (isFocusedState) 84 else 26, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        private fun ensureBitmapShaders(bg: Bitmap) {
            if (cachedShaderBitmap !== bg || interiorShader == null || bevelShader == null) {
                cachedShaderBitmap = bg
                interiorShader = android.graphics.BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                bevelShader = android.graphics.BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                interiorPaint.shader = interiorShader
                bevelPaint.shader = bevelShader
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 2f || h <= 2f) return
            rebuildShadersIfNeeded(w, h)

            val saved = canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.clipPath(clipPath)

            // 1. Optical refraction of blurred wallpaper mapped from window coordinates
            val bg = blurredWallpaper
            if (bg != null && !bg.isRecycled) {
                ensureBitmapShaders(bg)
                val res = anchorView.resources.displayMetrics
                val screenW = max(1f, res.widthPixels.toFloat())
                val screenH = max(1f, res.heightPixels.toFloat())
                anchorView.getLocationInWindow(loc)

                val sx = screenW / max(1f, bg.width.toFloat())
                val sy = screenH / max(1f, bg.height.toFloat())
                val cx = w * 0.5f
                val cy = h * 0.5f

                // Interior soft glass magnification around tile center
                val zoomIn = 1f + 0.035f * refractStrength
                interiorMatrix.reset()
                interiorMatrix.setScale(sx, sy)
                interiorMatrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
                interiorMatrix.postScale(zoomIn, zoomIn, cx, cy)
                interiorShader?.setLocalMatrix(interiorMatrix)
                interiorPaint.alpha = glassAlpha
                canvas.drawRect(0f, 0f, w, h, interiorPaint)

                // Optical bevel perimeter refraction ring (visibly bends backdrop along edges)
                val zoomBevel = 1f + (if (isFocusedState) 0.22f else 0.16f) * refractStrength
                bevelMatrix.reset()
                bevelMatrix.setScale(sx, sy)
                bevelMatrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
                bevelMatrix.postScale(zoomBevel, zoomBevel, cx, cy)
                bevelShader?.setLocalMatrix(bevelMatrix)
                bevelPaint.alpha = min(255, (glassAlpha * 0.92f).toInt())
                canvas.drawPath(bevelPath, bevelPaint)
            }

            // 2. Translucent obsidian glass body
            canvas.drawRect(0f, 0f, w, h, tintPaint)

            // 3. Diagonal optical specular caustic gleam
            canvas.drawRect(0f, 0f, w, h, causticPaint)

            // 4. Inner crystalline top/left highlight rim
            canvas.drawPath(innerRimPath, innerRimPaint)

            canvas.restoreToCount(saved)

            // 5. Delicate hairline crystal edge (pearl white glow on focus, zero heavy yellow wireframe)
            borderPaint.color = when {
                isPressedState -> Color.parseColor("#E6FFFFFF")
                isFocusedState -> focusStroke
                else -> normalStroke
            }
            borderPaint.strokeWidth = if (isFocusedState || isPressedState) focusStrokeWidthPx else strokeWidthPx
            val inset = borderPaint.strokeWidth * 0.5f
            rectF.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            val rad = min(radiusPx, min(w, h) * 0.5f)
            canvas.drawRoundRect(rectF, rad, rad, borderPaint)
        }

        fun setStrokeColors(normal: Int, focus: Int = focusStroke) {
            if (normalStroke != normal || focusStroke != focus) {
                normalStroke = normal
                focusStroke = focus
                invalidateSelf()
            }
        }

        override fun setAlpha(alpha: Int) {
            interiorPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            interiorPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun applyGlass(
        view: View,
        radiusDp: Float = 18f,
        baseTint: Int = Color.parseColor("#44090E18"),
        focusTint: Int = Color.parseColor("#72172338"),
        normalStroke: Int = Color.parseColor("#18FFFFFF"),
        focusStroke: Int = Color.parseColor("#A8FFFFFF"),
        refractStrength: Float = 1.0f,
        glassAlpha: Int = 225
    ): RefractiveGlassDrawable {
        val d = view.resources.displayMetrics.density
        val dr = RefractiveGlassDrawable(
            anchorView = view,
            radiusPx = radiusDp * d,
            baseTint = baseTint,
            focusTint = focusTint,
            normalStroke = normalStroke,
            focusStroke = focusStroke,
            strokeWidthPx = max(1.0f, 0.8f * d),
            focusStrokeWidthPx = max(1.6f, 1.3f * d),
            refractStrength = refractStrength,
            glassAlpha = glassAlpha
        )
        view.background = dr
        return dr
    }

    fun refreshGlassTree(view: View) {
        if (view.background is RefractiveGlassDrawable) {
            view.invalidate()
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                refreshGlassTree(view.getChildAt(i))
            }
        }
    }
}
