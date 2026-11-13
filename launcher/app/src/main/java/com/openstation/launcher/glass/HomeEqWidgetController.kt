package com.openstation.launcher.glass

import com.openstation.launcher.*
import com.openstation.launcher.R

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive Liquid Glass Home Equalizer Widget.
 * Allows cycling presets and adjusting Bass Boost + 5 macro EQ frequency bands
 * directly from the Home screen via TV remote D-pad or touch, syncing both
 * StationAudioService DSP and openstationd /api/audio/eq.
 */
class HomeEqWidgetController(
    private val context: Context,
    private val onCyclePreset: () -> Unit,
    private val onBandsCommitted: (preset: String, bands10: FloatArray, bassBoost: Int) -> Unit,
    private val onNavigateOutUpOrDown: (dir: Int) -> Boolean
) {
    private val handler = Handler(Looper.getMainLooper())
    private val presetLabels = mapOf(
        "balanced" to "Баланс",
        "bass_boost" to "Бас+",
        "vocal" to "Вокал",
        "night" to "Ночь",
        "rock" to "Рок",
        "electronic" to "Клуб",
        "flat" to "Flat",
        "custom" to "Кастом"
    )

    private val channelTitles = arrayOf("БАС", "60Hz", "250", "1k", "4k", "14k")
    private val bands10 = floatArrayOf(1f, 1.5f, 1f, 0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 1.5f)
    private var bassBoost = 20
    private var preset = "balanced"
    private var adjustingChannel = -1
    private var lastTouchUserEditMs = 0L

    lateinit var rootCard: LinearLayout
        private set
    private lateinit var btnPresetPill: TextView
    private lateinit var tvHint: TextView
    private val channelBoxes = ArrayList<LinearLayout>(6)
    private val valueTexts = ArrayList<TextView>(6)
    private val barViews = ArrayList<EqBarMeterView>(6)

    private val pendingPushRunnable = Runnable {
        onBandsCommitted(preset, bands10.copyOf(), bassBoost)
    }

    fun buildView(): LinearLayout {
        rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 20f,
                baseTint = Color.parseColor("#660B1324"),
                focusTint = Color.parseColor("#8813223B"),
                normalStroke = Color.parseColor("#4438BDF8"),
                focusStroke = Color.parseColor("#FFE8C686"),
                refractStrength = 1.05f
            )
            elevation = dp(10).toFloat()
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val tvTitle = TextView(context).apply {
            text = "ЭКВАЛАЙЗЕР"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnPresetPill = TextView(context).apply {
            text = "Баланс"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(3), dp(10), dp(3))
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor("#59182844"),
                focusTint = Color.parseColor("#99263F66"),
                normalStroke = Color.parseColor("#4DE8C686"),
                focusStroke = Color.parseColor("#FFE8C686")
            )
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(90).start()
                if (!hasFocus && adjustingChannel >= 0) setAdjustingChannel(-1)
            }
            setOnClickListener { onCyclePreset() }
        }
        headerRow.addView(tvTitle)
        headerRow.addView(btnPresetPill)
        rootCard.addView(headerRow)

        val barsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 6f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(108)
            )
        }

        for (ch in 0 until 6) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(3), dp(4), dp(3), dp(4))
                isFocusable = true
                isClickable = true
                background = makeChannelBg(adjusting = false, focused = false)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (ch < 5) marginEnd = dp(4)
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(85).start()
                    if (!hasFocus && adjustingChannel == ch) {
                        adjustingChannel = -1
                    }
                    v.background = makeChannelBg(adjusting = (adjustingChannel == ch), focused = hasFocus)
                    updateHintText()
                }
                setOnClickListener {
                    if (adjustingChannel == ch) {
                        setAdjustingChannel(-1)
                    } else {
                        setAdjustingChannel(ch)
                    }
                }
            }

            val tvVal = TextView(context).apply {
                text = "+0"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
            }

            val meter = EqBarMeterView(context, isBass = (ch == 0)).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), 0, 1f).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }

            val tvLbl = TextView(context).apply {
                text = channelTitles[ch]
                setTextColor(Color.parseColor(if (ch == 0) "#E8C686" else "#94A3B8"))
                textSize = 9.5f
                typeface = if (ch == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                maxLines = 1
            }

            col.addView(tvVal)
            col.addView(meter)
            col.addView(tvLbl)
            channelBoxes.add(col)
            valueTexts.add(tvVal)
            barViews.add(meter)
            barsRow.addView(col)
        }
        rootCard.addView(barsRow)

        tvHint = TextView(context).apply {
            text = "OK — настроить полосу · Выберите пресет выше"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9.5f
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
        }
        rootCard.addView(tvHint)

        refreshChannelVisuals()
        return rootCard
    }

    fun isOwningFocus(focused: View?): Boolean {
        if (focused == null || !::rootCard.isInitialized) return false
        return focused === btnPresetPill || channelBoxes.any { it === focused }
    }

    fun isAdjusting(): Boolean = adjustingChannel >= 0

    fun focusDefault() {
        if (::btnPresetPill.isInitialized) btnPresetPill.requestFocus()
    }

    fun handleKeyEvent(event: KeyEvent, focused: View?): Boolean {
        if (!isOwningFocus(focused)) return false
        val chIdx = channelBoxes.indexOf(focused)

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (chIdx >= 0 && adjustingChannel == chIdx) {
                        stepChannel(chIdx, +1)
                        return true
                    }
                    if (chIdx >= 0) {
                        btnPresetPill.requestFocus()
                        return true
                    }
                    return onNavigateOutUpOrDown(-1)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (chIdx >= 0 && adjustingChannel == chIdx) {
                        stepChannel(chIdx, -1)
                        return true
                    }
                    if (focused === btnPresetPill && channelBoxes.isNotEmpty()) {
                        channelBoxes[0].requestFocus()
                        return true
                    }
                    return onNavigateOutUpOrDown(+1)
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (chIdx > 0) {
                        val next = chIdx - 1
                        if (adjustingChannel >= 0) setAdjustingChannel(next)
                        channelBoxes[next].requestFocus()
                        return true
                    }
                    if (focused === btnPresetPill || chIdx == 0) {
                        return true // stay cleanly anchored at left edge of EQ
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (chIdx in 0 until 5) {
                        val next = chIdx + 1
                        if (adjustingChannel >= 0) setAdjustingChannel(next)
                        channelBoxes[next].requestFocus()
                        return true
                    }
                    if (adjustingChannel >= 0 && chIdx == 5) {
                        return true
                    }
                    // Allow parent DPAD_RIGHT handler to switch pages or leave widget
                    return false
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (adjustingChannel >= 0) {
                        setAdjustingChannel(-1)
                        return true
                    }
                }
            }
        }
        return false
    }

    fun syncFromDaemon(newPreset: String, newBands10: JSONArray?, newBass: Int?) {
        if (adjustingChannel >= 0 || (System.currentTimeMillis() - lastTouchUserEditMs) < 1500L) return
        var changed = false
        if (newPreset.isNotBlank() && newPreset != preset) {
            preset = newPreset
            changed = true
        }
        if (newBass != null) {
            val clamped = newBass.coerceIn(0, 100)
            if (clamped != bassBoost) {
                bassBoost = clamped
                changed = true
            }
        }
        if (newBands10 != null && newBands10.length() >= 10) {
            for (i in 0 until 10) {
                val g = newBands10.optDouble(i, bands10[i].toDouble()).toFloat().coerceIn(-12f, 12f)
                if (kotlin.math.abs(g - bands10[i]) > 0.05f) {
                    bands10[i] = g
                    changed = true
                }
            }
        }
        if (changed) {
            refreshChannelVisuals()
        }
    }

    fun setLocalPreset(newPreset: String) {
        preset = newPreset
        when (newPreset) {
            "flat" -> bands10.fill(0f)
            "bass_boost" -> floatArrayOf(4.5f, 4f, 3f, 1.5f, 0f, -0.5f, 0f, 0.5f, 1f, 1f).copyInto(bands10)
            "vocal" -> floatArrayOf(-2f, -1f, 0f, 1.5f, 3f, 3.5f, 2f, 1f, 0.5f, -0.5f).copyInto(bands10)
            "night" -> floatArrayOf(-3f, -2.5f, -1.5f, 0f, 1f, 1f, 0.5f, -0.5f, -2f, -3f).copyInto(bands10)
            "rock" -> floatArrayOf(3.5f, 2.5f, 1f, -0.5f, -1f, 0f, 1.5f, 2.5f, 3.5f, 3.5f).copyInto(bands10)
            "electronic" -> floatArrayOf(4f, 3.5f, 2f, 0.5f, -0.5f, 0f, 1f, 2f, 3.5f, 4f).copyInto(bands10)
            else -> floatArrayOf(1f, 1.5f, 1f, 0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 1.5f).copyInto(bands10)
        }
        refreshChannelVisuals()
    }

    fun updateLiveSpectrum(spec24: FloatArray, active: Boolean) {
        if (!::rootCard.isInitialized || rootCard.visibility != View.VISIBLE) return
        for (ch in 0 until 6) {
            val energy = if (active && spec24.size >= 24) {
                when (ch) {
                    0 -> (spec24[0] + spec24[1]) * 0.5f
                    1 -> (spec24[2] + spec24[3]) * 0.5f
                    2 -> (spec24[5] + spec24[7]) * 0.5f
                    3 -> (spec24[10] + spec24[12]) * 0.5f
                    4 -> (spec24[15] + spec24[17]) * 0.5f
                    else -> (spec24[20] + spec24[22]) * 0.5f
                }
            } else 0f
            barViews[ch].setSpectrumGlow(energy)
        }
    }

    private fun setAdjustingChannel(ch: Int) {
        adjustingChannel = ch
        for (i in 0 until channelBoxes.size) {
            channelBoxes[i].background = makeChannelBg(adjusting = (i == ch), focused = channelBoxes[i].isFocused)
        }
        updateHintText()
    }

    private fun updateHintText() {
        if (!::tvHint.isInitialized) return
        if (adjustingChannel >= 0) {
            tvHint.text = "▲▼ Изменить ${channelTitles[adjustingChannel]} · ◄► Полоса · OK готово"
            tvHint.setTextColor(Color.parseColor("#E8C686"))
        } else {
            tvHint.text = "OK на полосе — настройка уровня · Пресет: ${presetLabels[preset] ?: preset}"
            tvHint.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun stepChannel(ch: Int, dir: Int) {
        lastTouchUserEditMs = System.currentTimeMillis()
        preset = "custom"
        if (ch == 0) {
            bassBoost = (bassBoost + dir * 10).coerceIn(0, 100)
        } else {
            val b0 = (ch - 1) * 2
            val b1 = b0 + 1
            val curAvg = ((bands10[b0] + bands10[b1]) * 0.5f)
            val next = ((curAvg + dir * 1.5f).coerceIn(-12f, 12f) * 2f).roundToInt() / 2f
            bands10[b0] = next
            bands10[b1] = next
        }
        refreshChannelVisuals()
        try {
            StationAudioService.instance?.applyEq("custom", bands10.copyOf(), bassBoost)
        } catch (_: Throwable) {}
        handler.removeCallbacks(pendingPushRunnable)
        handler.postDelayed(pendingPushRunnable, 220L)
    }

    private fun refreshChannelVisuals() {
        if (::btnPresetPill.isInitialized) {
            btnPresetPill.text = presetLabels[preset] ?: preset
        }
        if (valueTexts.size < 6 || barViews.size < 6) return
        valueTexts[0].text = "${bassBoost}%"
        barViews[0].setNormalized(bassBoost / 100f)
        for (ch in 1 until 6) {
            val b0 = (ch - 1) * 2
            val avg = (bands10[b0] + bands10[b0 + 1]) * 0.5f
            val rounded = avg.roundToInt()
            valueTexts[ch].text = if (rounded > 0) "+$rounded" else "$rounded"
            barViews[ch].setNormalized((avg + 12f) / 24f)
        }
        updateHintText()
    }

    private fun makeChannelBg(adjusting: Boolean, focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            when {
                adjusting -> {
                    setColor(Color.parseColor("#5400D4FF"))
                    setStroke(dp(2), Color.parseColor("#FF00D4FF"))
                }
                focused -> {
                    setColor(Color.parseColor("#4D182946"))
                    setStroke(dp(2), Color.parseColor("#FFE8C686"))
                }
                else -> {
                    setColor(Color.parseColor("#2E08101E"))
                    setStroke(dp(1), Color.parseColor("#26FFFFFF"))
                }
            }
        }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private class EqBarMeterView(context: Context, private val isBass: Boolean) : View(context) {
        private var norm = 0.5f
        private var specEnergy = 0f
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#331E293B")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5538BDF8")
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val rect = RectF()
        private var lastH = 0f

        fun setNormalized(v: Float) {
            norm = v.coerceIn(0f, 1f)
            invalidate()
        }

        fun setSpectrumGlow(e: Float) {
            val next = specEnergy * 0.5f + e.coerceIn(0f, 1f) * 0.5f
            if (kotlin.math.abs(next - specEnergy) > 0.02f) {
                specEnergy = next
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 2f || h <= 4f) return
            if (h != lastH) {
                lastH = h
                fillPaint.shader = LinearGradient(
                    0f, h, 0f, 0f,
                    if (isBass) Color.parseColor("#F59E0B") else Color.parseColor("#0EA5E9"),
                    if (isBass) Color.parseColor("#FDE68A") else Color.parseColor("#2DD4BF"),
                    Shader.TileMode.CLAMP
                )
            }
            val barW = min(w * 0.48f, 16f)
            val cx = w * 0.5f
            val left = cx - barW * 0.5f
            val right = cx + barW * 0.5f
            val r = barW * 0.5f

            rect.set(left, 2f, right, h - 2f)
            canvas.drawRoundRect(rect, r, r, trackPaint)

            if (specEnergy > 0.04f) {
                val sh = max(barW, (h - 4f) * specEnergy)
                rect.set(left - 2f, h - 2f - sh, right + 2f, h - 2f)
                canvas.drawRoundRect(rect, r, r, specPaint)
            }

            val fillTop = (h - 4f) * (1f - norm) + 2f
            rect.set(left, min(h - 6f, fillTop), right, h - 2f)
            canvas.drawRoundRect(rect, r, r, fillPaint)

            val thumbY = fillTop.coerceIn(6f, h - 6f)
            canvas.drawCircle(cx, thumbY, barW * 0.56f, thumbPaint)
        }
    }
}
