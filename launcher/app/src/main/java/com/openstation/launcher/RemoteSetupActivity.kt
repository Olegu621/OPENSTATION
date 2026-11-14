package com.openstation.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class RemoteSetupActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var active = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StationAudioService.ensureStarted(this)
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(8, 13, 24))
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        root.addView(label("Подключение пульта", 28f))
        root.addView(label("Удерживайте «Домой» и «громкость −», пока пульт не засветится. Станция будет искать его до минуты.", 19f).apply {
            maxWidth = (640 * density).toInt()
        })
        status = label("Подготавливаем поиск…", 16f)
        root.addView(status)
        root.addView(label("Закрыть", 18f).apply {
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_tile)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((200 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        startSearch(0)
    }

    private fun startSearch(attempt: Int) {
        if (!active) return
        val manager = StationAudioService.instance?.bleRemoteManager
        if (manager == null) {
            if (attempt >= 10) status.text = "Служба пульта не запустилась. Закройте экран и повторите."
            else handler.postDelayed({ startSearch(attempt + 1) }, 500)
            return
        }
        manager.startAutoScanAndPair(60000) { _, detail ->
            handler.post { if (active) status.text = detail }
        }
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        StationAudioService.instance?.bleRemoteManager?.stopScan()
        super.onDestroy()
    }
}
