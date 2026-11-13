package com.openstation.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Phone remote: finds stations on the LAN and hosts the station's own web remote (its Material 3
 * mobile layout) in a WebView. Several stations -> list + switcher (native screen, also opened
 * from the web UI through the `OpenStationApp` JS bridge).
 */
class MainActivity : Activity() {

    private val accent = Color.parseColor("#8B7CFF")
    private val bg = Color.parseColor("#07070C")
    private val card = Color.parseColor("#16161F")
    private val text = Color.parseColor("#F5F5F7")
    private val muted = Color.parseColor("#9A9AA6")

    private val prefs by lazy { getSharedPreferences("remote", MODE_PRIVATE) }
    private lateinit var web: WebView
    private lateinit var picker: FrameLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar

    private var stations: List<Station> = emptyList()
    private var current: Station? = null
    private var scanning = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "OpenStationApp")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) showPicker("${current?.name ?: "Станция"} не отвечает")
                }
            }
        }
        picker = buildPicker()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(web)
            addView(picker)
        })

        // Last used station first (instant start), full sweep in the background.
        val last = prefs.getString("last", null)
        if (last != null) {
            val (h, p) = last.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: StationScanner.PORT) }
            showPicker(null)
            thread { StationScanner.probe(h, p)?.let { s -> runOnUiThread { if (current == null) open(s) } } }
        } else {
            showPicker(null)
        }
        rescan()
    }

    // ---------------------------------------------------------------- stations

    private fun manualHosts(): Set<String> = prefs.getStringSet("manual", emptySet())!!

    private fun rescan() {
        if (scanning) return
        scanning = true
        spinner.visibility = View.VISIBLE
        status.text = "Ищу станции в сети…"
        thread {
            val ipv4 = StationScanner.localIpv4(this)
            val found = StationScanner.scan(this, manualHosts())
            runOnUiThread {
                scanning = false
                spinner.visibility = View.GONE
                stations = found
                renderList()
                status.text = when {
                    ipv4 == null && found.isEmpty() -> "Подключитесь к Wi-Fi, в котором находится станция"
                    found.isEmpty() -> "Станции не найдены. Проверьте, что телефон и станция в одной сети"
                    found.size == 1 -> "Найдена 1 станция"
                    else -> "Найдено станций: ${found.size}"
                }
                when {
                    current != null -> Unit
                    found.size == 1 -> open(found[0])
                    else -> showPicker(null) // 0: empty state, many: choose
                }
            }
        }
    }

    private fun open(s: Station) {
        current = s
        prefs.edit().putString("last", s.key).apply()
        if (web.url?.startsWith(s.url) != true) {
            web.clearHistory()
            web.loadUrl(s.url)
        }
        picker.visibility = View.GONE
        renderList()
    }

    private fun showPicker(message: String?) {
        if (message != null) status.text = message
        renderList()
        picker.visibility = View.VISIBLE
    }

    private fun addManual() {
        val input = EditText(this).apply {
            hint = "192.168.1.50 или 192.168.1.50:8080"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Адрес станции")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("Подключить") { _, _ ->
                val raw = input.text.toString().trim().removePrefix("http://").trimEnd('/')
                if (raw.isEmpty()) return@setPositiveButton
                val h = raw.substringBefore(':')
                val p = raw.substringAfter(':', "").toIntOrNull() ?: StationScanner.PORT
                status.text = "Подключаюсь к $raw…"
                thread {
                    val s = StationScanner.probe(h, p)
                    runOnUiThread {
                        if (s == null) { status.text = "По адресу $raw станция не отвечает"; return@runOnUiThread }
                        prefs.edit().putStringSet("manual", manualHosts() + h).apply()
                        stations = (stations + s).distinctBy { it.key }
                        open(s)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun pressable(v: View, color: Int, r: Int, stroke: Int = 0) {
        v.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, r, stroke), null)
        v.isClickable = true
        v.isFocusable = true
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun buildPicker(): FrameLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        col.addView(tv("OPENSTATION", 13f, accent, bold = true).apply { letterSpacing = 0.12f })
        col.addView(tv("Станции", 30f, text, bold = true).apply { setPadding(0, dp(4), 0, dp(6)) })
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        spinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        }
        status = tv("", 15f, muted)
        statusRow.addView(spinner)
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(statusRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        val actions = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        fun action(label: String, primary: Boolean, onClick: () -> Unit) = tv(label, 15f, if (primary) Color.BLACK else text, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            pressable(this, if (primary) accent else card, 24)
            setOnClickListener { onClick() }
        }
        actions.addView(action("Искать снова", true) { rescan() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        actions.addView(action("Ввести адрес", false) { addManual() }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(actions)

        return FrameLayout(this).apply {
            setBackgroundColor(bg)
            isClickable = true // swallow touches meant for the WebView underneath
            addView(ScrollView(this@MainActivity).apply { addView(col) })
        }
    }

    private fun renderList() {
        list.removeAllViews()
        for (s in stations) {
            val selected = s.key == current?.key
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                pressable(this, card, 20, if (selected) accent else 0)
                setOnClickListener { open(s) }
            }
            val dot = View(this).apply { background = rounded(if (selected) accent else 0x33FFFFFF, 6) }
            row.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(tv(s.name, 18f, text, bold = true))
            texts.addView(tv(listOf(s.host + if (s.port != StationScanner.PORT) ":${s.port}" else "",
                s.version.takeIf { it.isNotBlank() }?.let { "v$it" }).filterNotNull().joinToString(" · "), 14f, muted))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) row.addView(tv("Открыта", 13f, accent, bold = true))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            picker.visibility == View.VISIBLE && current != null -> picker.visibility = View.GONE
            picker.visibility != View.VISIBLE && web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }

    /** Exposed to the station's web UI as `window.OpenStationApp`. */
    inner class Bridge {
        @JavascriptInterface fun pickStation() = runOnUiThread { showPicker(null); rescan() }
        @JavascriptInterface fun stationCount(): Int = stations.size
        @JavascriptInterface fun currentName(): String = current?.name ?: ""
    }
}
