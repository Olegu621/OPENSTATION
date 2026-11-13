package com.openstation.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * VolumeHud.dc.html: top-centre glass pill (520x96) with 20 rising segments, level number and
 * the current output route. Hosted by [VolumeOverlayManager] as a system overlay (over the
 * launcher and every other app); [bindToast] reuses the same pill for short status messages.
 */
class VolumeHudView(context: Context) : LinearLayout(context) {

    companion object {
        /** Updated from daemon status (config.audio.outputRoute) by MainActivity. */
        @Volatile
        var routeLabel: String = "Динамики станции"

        fun routeLabelFor(route: String): String = when (route) {
            "headphones" -> "Наушники"
            "both" -> "Динамики + наушники"
            "hdmi" -> "HDMI · телевизор"
            else -> "Динамики станции"
        }
    }

    private val iconBg = Theme.oval(Theme.alpha(Theme.accent, 0.22f))
    private val ivIcon = ImageView(context)
    private val bars = Bars(context)
    private val tvTitle = context.eyebrow("", Theme.accent, 13f).apply { visibility = View.GONE }
    private val tvRoute = context.label("", 14f, Theme.alpha(Theme.TEXT, 0.7f))
    private val tvLevel = context.label("", 34f, Theme.TEXT, 700).apply { gravity = Gravity.END }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(28), 0, context.dp(28), 0)
        background = Theme.rounded(Theme.SHEET, context.dpf(48))
        elevation = context.dpf(20)

        val iconBox = FrameLayout(context).apply {
            background = iconBg
            addView(ivIcon, FrameLayout.LayoutParams(context.dp(26), context.dp(26), Gravity.CENTER))
        }
        addView(iconBox, LayoutParams(context.dp(52), context.dp(52)).apply { marginEnd = context.dp(20) })

        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(tvTitle)
            addView(bars, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(26)))
            addView(tvRoute, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            })
        }
        addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(tvLevel, LayoutParams(context.dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        bind(50, false)
    }

    fun bind(percent: Int, muted: Boolean) {
        val pct = percent.coerceIn(0, 100)
        val silent = muted || pct == 0
        setToastMode(false)
        iconBg.setColor(if (silent) Theme.DANGER_BG else Theme.alpha(Theme.accent, 0.22f))
        ivIcon.setTintedIcon(R.drawable.ic_hw_speaker, if (silent) Theme.DANGER_FG else Theme.accent)
        bars.lit = if (silent) 0 else (pct + 2) / 5
        bars.invalidate()
        tvLevel.text = if (silent) "—" else pct.toString()
        tvRoute.text = if (silent) "Звук выключен · $routeLabel" else routeLabel
        contentDescription = "Громкость $pct процентов"
    }

    /** Status message in the same pill (Bluetooth, BLE remote): eyebrow title + one line. */
    fun bindToast(title: String, subtitle: String, danger: Boolean) {
        setToastMode(true)
        val fg = if (danger) Theme.DANGER_FG else Theme.accent
        iconBg.setColor(if (danger) Theme.DANGER_BG else Theme.alpha(Theme.accent, 0.22f))
        ivIcon.setTintedIcon(R.drawable.ic_hw_bluetooth, fg)
        tvTitle.text = title.uppercase()
        tvTitle.setTextColor(fg)
        tvRoute.text = subtitle
        tvRoute.visibility = if (subtitle.isNotBlank()) View.VISIBLE else View.GONE
        contentDescription = "$title. $subtitle"
    }

    private fun setToastMode(toast: Boolean) {
        tvTitle.visibility = if (toast) View.VISIBLE else View.GONE
        bars.visibility = if (toast) View.GONE else View.VISIBLE
        tvLevel.visibility = if (toast) View.GONE else View.VISIBLE
        tvRoute.visibility = View.VISIBLE
    }

    private class Bars(context: Context) : View(context) {
        var lit = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gap = context.dpf(4)
        private val radius = context.dpf(3)
        private val unit = context.dpf(1)

        override fun onDraw(canvas: Canvas) {
            val w = (width - gap * 19) / 20f
            for (i in 0 until 20) {
                val h = ((10 + i) * unit).coerceAtMost(height.toFloat())
                val x = i * (w + gap)
                paint.color = if (i < lit) Theme.accent else Theme.alpha(Color.WHITE, 0.14f)
                canvas.drawRoundRect(x, height - h, x + w, height.toFloat(), radius, radius, paint)
            }
        }
    }
}
