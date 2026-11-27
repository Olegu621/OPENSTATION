package com.openstation.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class RemoteSetupActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var scanDot: View
    private lateinit var scanDotBg: GradientDrawable
    private lateinit var scanBadgeTv: TextView
    private lateinit var btnRetry: TextView
    private lateinit var btnClose: TextView
    private var dotAnimator: ObjectAnimator? = null
    private var active = true

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        LiquidGlassKit.ensureInitialized(this)
        StationAudioService.ensureStarted(this)

        val root = FrameLayout(this).apply {
            val wall = LiquidGlassKit.sharpWallpaper
            background = if (wall != null) {
                BitmapDrawable(resources, wall)
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#030509"), Color.parseColor("#0A1526"))
                )
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(34), dp(28), dp(34), dp(26))
        }
        LiquidGlassKit.applyGlass(
            card,
            radiusDp = 26f,
            baseTint = Color.parseColor("#6A09111E"),
            focusTint = Color.parseColor("#6A09111E"),
            normalStroke = Color.parseColor("#2AFFFFFF"),
            focusStroke = Color.parseColor("#8C7DB8D6")
        )

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(14), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#220EA5E9"))
                setStroke(dp(1), Color.parseColor("#5538BDF8"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        scanDotBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#38BDF8"))
        }
        scanDot = View(this).apply {
            background = scanDotBg
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(8)
            }
        }
        scanBadgeTv = TextView(this).apply {
            text = "ПОИСК BLUETOOTH LE ПУЛЬТА"
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 11f
            letterSpacing = 0.09f
            typeface = Typeface.DEFAULT_BOLD
        }
        badgeRow.addView(scanDot)
        badgeRow.addView(scanBadgeTv)
        card.addView(badgeRow)

        card.addView(TextView(this).apply {
            text = "Подключение пульта"
            setTextColor(Color.WHITE)
            textSize = 25f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        card.addView(TextView(this).apply {
            text = "Удерживайте кнопки «Домой» и «Громкость −» на пульте ~5 сек, пока индикатор не начнёт мигать.\nСтанция подключит пульт автоматически."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.0f)
            maxWidth = dp(520)
            setPadding(0, dp(10), 0, dp(16))
        })

        statusTv = TextView(this).apply {
            text = "Подготовка BLE-адаптера…"
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#330D1B2E"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        }
        card.addView(statusTv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnRetry = makeGlassPill("↻ Повторить поиск", primary = true) {
            StationAudioService.instance?.bleRemoteManager?.stopScan()
            setScanVisual("scanning", "Повторное сканирование BLE-эфира…")
            startSearch(0, forceScan = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(12)
            }
        }

        val btnForget = makeGlassPill("🧹 Забыть и заново", primary = false) {
            val mgr = StationAudioService.instance?.bleRemoteManager
            if (mgr == null) {
                setScanVisual("error", "Служба пульта ещё запускается.")
                return@makeGlassPill
            }
            setScanVisual("scanning", "Очищаю старое сопряжение и запускаю поиск…")
            mgr.forgetBondedRemotes { _, _ ->
                if (active) startSearch(0, forceScan = true)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(12)
            }
        }

        btnClose = makeGlassPill("Закрыть", primary = false) {
            finish()
        }

        btnRow.addView(btnRetry)
        btnRow.addView(btnForget)
        btnRow.addView(btnClose)
        card.addView(btnRow)

        root.addView(
            card,
            FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        setContentView(root)

        startDotPulse()
        startSearch(0, forceScan = false)
        btnClose.requestFocus()
    }

    private fun makeGlassPill(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(if (primary) "#F0F9FF" else "#FFE8C686"))
            setPadding(dp(22), dp(11), dp(22), dp(11))
            isFocusable = true
            isClickable = true
            LiquidGlassKit.applyGlass(
                this,
                radiusDp = 999f,
                baseTint = Color.parseColor(if (primary) "#550EA5E9" else "#44172033"),
                focusTint = Color.parseColor(if (primary) "#9938BDF8" else "#77263452"),
                normalStroke = Color.parseColor(if (primary) "#6638BDF8" else "#44E8C686"),
                focusStroke = Color.WHITE
            )
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(100).start()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun startDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = ObjectAnimator.ofFloat(scanDot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopDotPulse() {
        dotAnimator?.cancel()
        dotAnimator = null
        scanDot.alpha = 1f
    }

    private fun setScanVisual(state: String, detail: String) {
        statusTv.text = detail
        when (state) {
            "connected" -> {
                stopDotPulse()
                scanDotBg.setColor(Color.parseColor("#10B981"))
                scanBadgeTv.text = "ПУЛЬТ ПОДКЛЮЧЁН"
                scanBadgeTv.setTextColor(Color.parseColor("#6EE7B7"))
                statusTv.setTextColor(Color.parseColor("#6EE7B7"))
                val mgr = StationAudioService.instance?.bleRemoteManager
                val st = mgr?.getRemoteStatus()
                if (st != null && st.batteryPct >= 0 && !detail.contains("%")) {
                    statusTv.text = "$detail · Заряд ${st.batteryPct}%"
                }
            }
            "not_found", "error" -> {
                stopDotPulse()
                scanDotBg.setColor(Color.parseColor("#F59E0B"))
                scanBadgeTv.text = "НУЖЕН ПОВТОР ПОИСКА"
                scanBadgeTv.setTextColor(Color.parseColor("#FDE68A"))
                statusTv.setTextColor(Color.parseColor("#FDE68A"))
                btnRetry.requestFocus()
            }
            else -> {
                scanDotBg.setColor(Color.parseColor("#38BDF8"))
                scanBadgeTv.text = "ПОИСК BLUETOOTH LE ПУЛЬТА"
                scanBadgeTv.setTextColor(Color.parseColor("#7DD3FC"))
                statusTv.setTextColor(Color.parseColor("#E0F2FE"))
                if (dotAnimator == null) startDotPulse()
            }
        }
    }

    private fun startSearch(attempt: Int, forceScan: Boolean = false) {
        if (!active) return
        val manager = StationAudioService.instance?.bleRemoteManager
        if (manager == null) {
            if (attempt >= 10) {
                setScanVisual("error", "Служба пульта не запустилась. Нажмите «Повторить поиск».")
            } else {
                handler.postDelayed({ startSearch(attempt + 1, forceScan) }, 500)
            }
            return
        }
        manager.startAutoScanAndPair(60000L, forceScan = forceScan) { state, detail ->
            handler.post {
                if (active) setScanVisual(state, detail)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        active = false
        stopDotPulse()
        handler.removeCallbacksAndMessages(null)
        StationAudioService.instance?.bleRemoteManager?.stopScan()
        super.onDestroy()
    }
}
