package com.openstation.launcher.glass

import com.openstation.launcher.*
import com.openstation.launcher.R

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.StateSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

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

    fun makeFocusableGlassSelector(
        ctx: Context,
        normalStart: String = "#B8121D30",
        normalEnd: String = "#C40A101D",
        normalStroke: String = "#2AFFFFFF",
        focusStroke: String = "#E8C686",
        radiusDp: Int = 16
    ): StateListDrawable {
        val r = dp(ctx, radiusDp).toFloat()
        val focused = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#EB20324E"), Color.parseColor("#EB101B2E"))
        ).apply {
            setStroke(dp(ctx, 2), Color.parseColor(focusStroke))
            cornerRadius = r
        }
        val pressed = GradientDrawable().apply {
            setColor(Color.parseColor("#DB1D3856"))
            setStroke(dp(ctx, 2), Color.parseColor("#38BDF8"))
            cornerRadius = r
        }
        val normal = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(normalStart), Color.parseColor(normalEnd))
        ).apply {
            setStroke(dp(ctx, 1), Color.parseColor(normalStroke))
            cornerRadius = r
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(StateSet.WILD_CARD, normal)
        }
    }

    fun makeDangerPillSelector(ctx: Context): StateListDrawable {
        val r = dp(ctx, 999).toFloat()
        val focused = GradientDrawable().apply {
            setColor(Color.parseColor("#E67F1D1D"))
            setStroke(dp(ctx, 2), Color.parseColor("#FDA4AF"))
            cornerRadius = r
        }
        val normal = GradientDrawable().apply {
            setColor(Color.parseColor("#B34C1D28"))
            setStroke(dp(ctx, 1), Color.parseColor("#66FB7185"))
            cornerRadius = r
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(StateSet.WILD_CARD, normal)
        }
    }

    fun makeCyanPillSelector(ctx: Context): StateListDrawable {
        val r = dp(ctx, 999).toFloat()
        val focused = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#F00891B2"), Color.parseColor("#F00369A1"))
        ).apply {
            setStroke(dp(ctx, 2), Color.WHITE)
            cornerRadius = r
        }
        val normal = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#CC0EA5E9"), Color.parseColor("#CC0284C7"))
        ).apply {
            setStroke(dp(ctx, 1), Color.parseColor("#66BAE6FD"))
            cornerRadius = r
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(StateSet.WILD_CARD, normal)
        }
    }

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
                    setTextColor(Color.parseColor("#0B0F17"))
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundResource(R.drawable.glass_bg_pill_gold)
                }
                PillStyle.CYAN -> {
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    background = makeCyanPillSelector(ctx)
                }
                PillStyle.DANGER -> {
                    setTextColor(Color.parseColor("#FFE4E6"))
                    typeface = Typeface.DEFAULT_BOLD
                    background = makeDangerPillSelector(ctx)
                }
                PillStyle.GLASS -> {
                    setTextColor(Color.parseColor("#E2E8F0"))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setBackgroundResource(R.drawable.glass_bg_pill)
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
