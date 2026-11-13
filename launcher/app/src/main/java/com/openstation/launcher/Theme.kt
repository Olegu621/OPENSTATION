package com.openstation.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * "Ambient" design tokens shared by every screen. The accent colour lives in exactly one place,
 * res/values/colors.xml (`accent`, also used by the XML drawables); [init] copies it into [accent]
 * and must run in every entry point (activities, service) before views are built.
 */
object Theme {
    @Volatile
    var accent: Int = Color.GRAY
        private set

    /** true = the upstream author's "Liquid Glass" UI (package [com.openstation.launcher.glass]). */
    @Volatile
    var glass: Boolean = false
        private set

    fun init(ctx: Context) {
        glass = ctx.getSharedPreferences("ui_theme", Context.MODE_PRIVATE).getBoolean("glass", false)
        accent = if (glass) 0xFFE8C686.toInt() else ctx.getColor(R.color.accent) // glass: author's gold
    }

    /**
     * The Ambient activities stay the public entry points (HOME, daemon `am start -n`). When the
     * glass theme is active they hand the same intent to their glass twin and finish at once.
     */
    fun forwardIfGlass(a: android.app.Activity, twin: Class<out android.app.Activity>): Boolean {
        if (!glass) return false
        a.startActivity(android.content.Intent(a.intent).setClass(a, twin))
        a.finish()
        a.overridePendingTransition(0, 0)
        return true
    }

    fun switchTo(ctx: Context, glass: Boolean) {
        ctx.getSharedPreferences("ui_theme", Context.MODE_PRIVATE).edit().putBoolean("glass", glass).commit()
        init(ctx)
        StationAudioService.instance?.recreateOverlays()
        ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    const val BG = 0xFF07070C.toInt()
    const val TEXT = 0xFFF5F5F7.toInt()
    const val ON_ACCENT = 0xFF0A0A0F.toInt()
    const val DANGER_FG = 0xFFFFB4B4.toInt()
    val TEXT_MUTED = alpha(TEXT, 0.68f)
    val TEXT_FAINT = alpha(TEXT, 0.50f)
    val SURFACE = Color.argb(128, 12, 12, 18)
    val SHEET = Color.argb(209, 14, 14, 20)
    val DIALOG = Color.argb(240, 20, 20, 28)
    val CHIP = alpha(Color.WHITE, 0.08f)
    val BUTTON = alpha(Color.WHITE, 0.10f)
    val ROW = alpha(Color.WHITE, 0.06f)
    val FOCUS_FILL = alpha(Color.WHITE, 0.14f)
    val TRACK = alpha(Color.WHITE, 0.18f)
    val SWITCH_OFF = alpha(Color.WHITE, 0.22f)
    val DANGER_BG = Color.argb(46, 255, 95, 95)

    /** "#AARRGGBB" accent string for helpers that take hex colours (market badges). */
    fun hex(a: Float): String = String.format("#%08X", alpha(accent, a))

    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)

    fun rounded(color: Int, radius: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun oval(color: Int, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Background with a clearly visible D-pad focus state: 3px ring + brighter fill. */
    fun focusable(
        normal: Int,
        radius: Float,
        ringPx: Int,
        ring: Int = accent,
        focusedFill: Int = FOCUS_FILL,
        isOval: Boolean = false
    ): StateListDrawable = StateListDrawable().apply {
        fun mk(fill: Int, stroke: Int) =
            if (isOval) oval(fill, if (stroke != 0) ringPx else 0, stroke) else rounded(fill, radius, if (stroke != 0) ringPx else 0, stroke)
        addState(intArrayOf(android.R.attr.state_focused), mk(focusedFill, ring))
        addState(intArrayOf(android.R.attr.state_pressed), mk(focusedFill, ring))
        addState(intArrayOf(), mk(normal, 0))
    }

    /** Accent-filled orb used where the animated AliceOrbView is overkill (alarm, mini states). */
    fun orbGradient(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(Color.WHITE, accent, alpha(accent, 0.7f))
        gradientRadius = radiusPx
        setGradientCenter(0.34f, 0.28f)
    }

    /** Cover placeholder: linear 135deg accent -> #2A1E4A -> #0E0E16. */
    fun coverPlaceholder(radius: Float): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(alpha(accent, 0.95f), 0xFF2A1E4A.toInt(), 0xFF0E0E16.toInt())
    ).apply { cornerRadius = radius }

    /** Main.dc.html background: #07070C + three soft radial glows. */
    fun ambientBackground(): Drawable = AmbientDrawable(
        listOf(
            Glow(0.18f, 0.72f, 0.49f, 0.33f, alpha(accent, 0.55f)),
            Glow(0.85f, 0.20f, 0.38f, 0.27f, alpha(accent, 0.28f)),
            Glow(0.70f, 0.95f, 0.33f, 0.27f, Color.argb(153, 56, 40, 90))
        ),
        scrim = true
    )

    /** Single centred glow (Alice, alarm, timers, screensaver screens). */
    fun glowBackground(cx: Float, cy: Float, a: Float, base: Int = BG): Drawable =
        AmbientDrawable(listOf(Glow(cx, cy, 0.49f, 0.35f, alpha(accent, a))), scrim = false, base = base)

    /** rx/ry are fractions of the width (CSS ellipse radius * 0.7 transparent stop). */
    class Glow(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val color: Int)

    private class AmbientDrawable(
        private val glows: List<Glow>,
        private val scrim: Boolean,
        private val base: Int = BG
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val shaders = arrayOfNulls<RadialGradient>(glows.size)
        private var scrimShader: Shader? = null

        override fun onBoundsChange(bounds: Rect) {
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            glows.forEachIndexed { i, g ->
                shaders[i] = RadialGradient(0f, 0f, g.rx * w, g.color, g.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            scrimShader = if (scrim) android.graphics.LinearGradient(
                0f, 0f, 0f, bounds.height().toFloat(),
                intArrayOf(Color.argb(89, 6, 6, 10), Color.argb(13, 6, 6, 10), Color.argb(191, 6, 6, 10)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            ) else null
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            canvas.drawColor(base)
            glows.forEachIndexed { i, g ->
                val sh = shaders[i] ?: return@forEachIndexed
                paint.shader = sh
                canvas.save()
                canvas.translate(b.left + g.cx * w, b.top + g.cy * h)
                canvas.scale(1f, g.ry / g.rx)
                canvas.drawCircle(0f, 0f, g.rx * w, paint)
                canvas.restore()
            }
            scrimShader?.let {
                paint.shader = it
                canvas.drawRect(b, paint)
            }
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}

// ---------------------------------------------------------------------------------------------
// Small view factories shared by the launcher screens (all UI is built in code).
// ---------------------------------------------------------------------------------------------

/**
 * The Ambient mockups are drawn on a 1280x720 canvas. TV boxes report very different dp sizes
 * (the Station Max is 1920x1080 @ xhdpi = 960x540 dp), so every size goes through one factor that
 * maps "mockup px" onto the real screen. 1.0 on a 1280x720-dp display, 0.75 on the Station Max.
 */
fun Context.uiScale(): Float {
    val m = resources.displayMetrics
    val wDp = maxOf(m.widthPixels, m.heightPixels) / m.density
    val hDp = minOf(m.widthPixels, m.heightPixels) / m.density
    return minOf(wDp / 1280f, hDp / 720f).coerceIn(0.5f, 2f)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density * uiScale() + 0.5f).toInt()

fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density * uiScale()

/** CSS-ish font weight -> Roboto family. */
fun typefaceFor(weight: Int): Typeface = when {
    weight <= 200 -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
    weight <= 300 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    weight < 500 -> Typeface.create("sans-serif", Typeface.NORMAL)
    weight < 700 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    else -> Typeface.create("sans-serif", Typeface.BOLD)
}

fun Context.label(
    str: CharSequence,
    sp: Float,
    color: Int = Theme.TEXT,
    weight: Int = 400,
    lines: Int = 1
): TextView = TextView(this).apply {
    text = str
    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * resources.displayMetrics.density * resources.configuration.fontScale * uiScale())
    setTextColor(color)
    typeface = typefaceFor(weight)
    includeFontPadding = false
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
}

/** Upper-case section/eyebrow label (13px, 700, letter-spacing 1.5px). */
fun Context.eyebrow(str: String, color: Int = Theme.TEXT_FAINT, sp: Float = 12f): TextView =
    label(str.uppercase(), sp, color, 700).apply { letterSpacing = 0.12f }

fun Context.icon(res: Int, sizeDp: Int, tint: Int): ImageView = ImageView(this).apply {
    setImageDrawable(getDrawable(res)?.mutate()?.apply { setTint(tint) })
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun ImageView.setTintedIcon(res: Int, tint: Int) {
    setImageDrawable(context.getDrawable(res)?.mutate()?.apply { setTint(tint) })
}

/** Pill button: primary = accent fill + white ring on focus; secondary = white 10% + accent ring. */
fun Context.pillButton(text: String, primary: Boolean, heightDp: Int = 56, onClick: () -> Unit): TextView =
    label(text, if (heightDp >= 60) 20f else 17f, if (primary) Theme.ON_ACCENT else Theme.TEXT, if (primary) 700 else 600).apply {
        gravity = Gravity.CENTER
        setPadding(dp(28), 0, dp(28), 0)
        val r = dpf(heightDp) / 2f
        background = if (primary) {
            Theme.focusable(Theme.accent, r, dp(3), Color.WHITE, focusedFill = Theme.accent)
        } else {
            Theme.focusable(Theme.BUTTON, r, dp(3), Color.WHITE, focusedFill = Theme.alpha(Color.WHITE, 0.18f))
        }
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp))
    }

/** Round icon button (player controls). */
fun Context.circleButton(res: Int, sizeDp: Int, iconDp: Int, primary: Boolean, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = if (primary) {
            Theme.focusable(Theme.accent, 0f, dp(3), Color.WHITE, focusedFill = Theme.accent, isOval = true)
        } else {
            Theme.focusable(Theme.BUTTON, 0f, dp(3), Theme.accent, focusedFill = Theme.alpha(Color.WHITE, 0.2f), isOval = true)
        }
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setTintedIcon(res, if (primary) Theme.ON_ACCENT else Theme.TEXT)
            layoutParams = FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER)
        })
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

val FrameLayout.iconView: ImageView get() = getChildAt(0) as ImageView

/** Thin rounded progress bar in the accent colour (max = 1000). */
fun Context.accentProgress(heightDp: Int): ProgressBar =
    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        val r = dpf(heightDp) / 2f
        progressDrawable = LayerDrawable(
            arrayOf(
                Theme.rounded(Theme.TRACK, r),
                ClipDrawable(Theme.rounded(Theme.accent, r), Gravity.START, ClipDrawable.HORIZONTAL)
            )
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

/** iOS-style switch (46x28 track, 22 thumb) used inside settings rows. */
class SwitchPill(context: Context) : FrameLayout(context) {
    private val track = Theme.rounded(Theme.SWITCH_OFF, context.dpf(14))
    private val thumb = View(context).apply {
        background = Theme.oval(Color.WHITE)
    }
    var checked: Boolean = false
        set(value) {
            field = value
            track.setColor(if (value) Theme.accent else Theme.SWITCH_OFF)
            (thumb.layoutParams as LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (value) Gravity.END else Gravity.START)
            thumb.requestLayout()
        }

    init {
        background = track
        val pad = context.dp(3)
        setPadding(pad, pad, pad, pad)
        addView(thumb, LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER_VERTICAL or Gravity.START))
        layoutParams = android.widget.LinearLayout.LayoutParams(context.dp(46), context.dp(28))
    }
}
