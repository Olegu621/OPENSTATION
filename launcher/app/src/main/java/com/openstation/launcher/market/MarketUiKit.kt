package com.openstation.launcher.market

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.openstation.launcher.R
import com.openstation.launcher.Theme

object MarketUiKit {

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun attachFocusScale(view: View, scale: Float = 1.04f, durationMs: Long = 95L) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(durationMs)
                .start()
        }
    }

    fun makeGlassSurface(
        ctx: Context,
        bgColorHex: String = "#CC0C1424",
        strokeColorHex: String = "#33FFFFFF",
        radiusDp: Int = 18
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColorHex))
            setStroke(dp(ctx, 1), Color.parseColor(strokeColorHex))
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }
    }

    fun makeFocusableGlassSelector(ctx: Context, radiusDp: Int = 16): StateListDrawable =
        Theme.focusable(Theme.ROW, dp(ctx, radiusDp).toFloat(), dp(ctx, 3))

    fun makeDangerPillSelector(ctx: Context): StateListDrawable =
        Theme.focusable(Theme.DANGER_BG, dp(ctx, 999).toFloat(), dp(ctx, 3), Theme.DANGER_FG,
            focusedFill = Theme.alpha(Theme.DANGER_FG, 0.30f))

    fun makeCyanPillSelector(ctx: Context): StateListDrawable =
        Theme.focusable(Theme.accent, dp(ctx, 999).toFloat(), dp(ctx, 3), Color.WHITE, focusedFill = Theme.accent)

    fun createPill(
        ctx: Context,
        label: String,
        style: PillStyle = PillStyle.GLASS,
        onClick: () -> Unit
    ): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(ctx, 15), dp(ctx, 8), dp(ctx, 15), dp(ctx, 8))
            when (style) {
                PillStyle.GOLD -> {
                    setTextColor(Theme.ON_ACCENT)
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundResource(R.drawable.bg_pill_gold)
                }
                PillStyle.CYAN -> {
                    setTextColor(Theme.ON_ACCENT)
                    typeface = Typeface.DEFAULT_BOLD
                    background = makeCyanPillSelector(ctx)
                }
                PillStyle.DANGER -> {
                    setTextColor(Theme.DANGER_FG)
                    typeface = Typeface.DEFAULT_BOLD
                    background = makeDangerPillSelector(ctx)
                }
                PillStyle.GLASS -> {
                    setTextColor(Theme.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setBackgroundResource(R.drawable.bg_pill)
                }
            }
            attachFocusScale(this, 1.05f, 85L)
            setOnClickListener { onClick() }
        }
    }

    fun createBadgeChip(
        ctx: Context,
        textStr: String,
        textColorHex: String,
        bgColorHex: String,
        borderColorHex: String
    ): TextView {
        return TextView(ctx).apply {
            text = textStr
            textSize = 10.5f
            maxLines = 1
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(textColorHex))
            setPadding(dp(ctx, 9), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColorHex))
                setStroke(dp(ctx, 1), Color.parseColor(borderColorHex))
                cornerRadius = dp(ctx, 999).toFloat()
            }
        }
    }

    fun makeHorizontalDivider(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 8)
            }
        }
    }

    enum class PillStyle {
        GLASS,
        GOLD,
        CYAN,
        DANGER
    }
}
