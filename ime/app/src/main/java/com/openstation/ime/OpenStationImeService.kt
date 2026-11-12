package com.openstation.ime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class OpenStationImeService : InputMethodService() {

    private enum class LayoutMode { RU, EN, SYM }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootLayout: LinearLayout? = null
    private var keysContainer: LinearLayout? = null
    private var langBadge: TextView? = null
    private var mode = LayoutMode.RU
    private var shifted = false

    @Volatile private var running = false
    private var udpSocket: DatagramSocket? = null

    private val gridButtons = ArrayList<List<Button>>()
    private var curRow = 0
    private var curCol = 0
    private val pendingKeyUpSwallow = HashSet<Int>()
    private val seenSeqs = ArrayDeque<String>(16)

    private val ruRows = listOf(
        listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х", "ъ"),
        listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э", "ё"),
        listOf("⇧", "я", "ч", "с", "м", "и", "т", "ь", "б", "ю", ".", "⌫"),
        listOf("123", "RU/EN", ",", "Пробел", "-", "_", "⏎")
    )

    private val enRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "-", "_"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'", "/"),
        listOf("⇧", "z", "x", "c", "v", "b", "n", "m", ",", ".", "?", "⌫"),
        listOf("123", "RU/EN", "@", "Пробел", ":", "!", "⏎")
    )

    private val symRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "+", "="),
        listOf("@", "#", "$", "%", "&", "*", "(", ")", "[", "]", "{", "}"),
        listOf("/", "\\", "|", "~", "<", ">", "\"", "'", ":", ";", "?", "⌫"),
        listOf("АБВ", "ABC", ",", "Пробел", ".", "-", "⏎")
    )

    override fun onCreate() {
        super.onCreate()
        running = true
        startUdpListener()
    }

    override fun onDestroy() {
        running = false
        try { udpSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onCreateInputView(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0B0E14"))
            val padH = dp(28)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val title = TextView(this).apply {
            text = "OpenStation Клавиатура"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(this).apply {
            setTextColor(Color.parseColor("#00D4FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        langBadge = badge
        header.addView(title)
        header.addView(badge)
        outer.addView(header)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        outer.addView(container)
        rootLayout = outer
        keysContainer = container
        rebuildKeys(null)
        return outer
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shifted = false
        rebuildKeys(null)
    }

    private fun startUdpListener() {
        thread(isDaemon = true, name = "ime-udp") {
            while (running) {
                var sock: DatagramSocket? = null
                try {
                    sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), UDP_PORT))
                    }
                    udpSocket = sock
                    val buf = ByteArray(8192)
                    while (running) {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val raw = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val obj = JSONObject(raw)
                        if (obj.optString("cmd") == "text") {
                            val txt = obj.optString("text", "")
                            val seq = obj.optString("seq", "")
                            if (txt.isNotEmpty() && markSeqNew(seq)) {
                                mainHandler.post { commitRemoteText(txt) }
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (running) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                    }
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                    if (udpSocket === sock) udpSocket = null
                }
            }
        }
    }

    @Synchronized
    private fun markSeqNew(seq: String): Boolean {
        if (seq.isEmpty()) return true
        if (seenSeqs.contains(seq)) return false
        if (seenSeqs.size >= 16) seenSeqs.removeFirst()
        seenSeqs.addLast(seq)
        return true
    }

    private fun commitRemoteText(txt: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(txt, 1)
    }

    private fun makeKeyBg(): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(Color.parseColor("#00A8CC"))
            setStroke(dp(2), Color.parseColor("#E0F2FE"))
            cornerRadius = dp(8).toFloat()
        }
        val pressed = GradientDrawable().apply {
            setColor(Color.parseColor("#7C3AED"))
            cornerRadius = dp(8).toFloat()
        }
        val normal = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            setStroke(1, Color.parseColor("#334155"))
            cornerRadius = dp(8).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun currentRows(): List<List<String>> = when (mode) {
        LayoutMode.RU -> ruRows
        LayoutMode.EN -> enRows
        LayoutMode.SYM -> symRows
    }

    private fun updateSelection(r: Int, c: Int) {
        if (gridButtons.isEmpty()) return
        curRow = r.coerceIn(0, gridButtons.lastIndex)
        curCol = c.coerceIn(0, gridButtons[curRow].lastIndex)
        for (ri in gridButtons.indices) {
            val rowBtns = gridButtons[ri]
            for (ci in rowBtns.indices) {
                val sel = (ri == curRow && ci == curCol)
                rowBtns[ci].isSelected = sel
                if (sel) rowBtns[ci].requestFocus()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isInputViewShown && gridButtons.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val rowLen = gridButtons[curRow].size
                    updateSelection(curRow, (curCol - 1 + rowLen) % rowLen)
                    pendingKeyUpSwallow.add(keyCode)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val rowLen = gridButtons[curRow].size
                    updateSelection(curRow, (curCol + 1) % rowLen)
                    pendingKeyUpSwallow.add(keyCode)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (curRow > 0) {
                        val oldLen = gridButtons[curRow].size
                        val nextRow = curRow - 1
                        val newLen = gridButtons[nextRow].size
                        val mappedCol = (((curCol + 0.5) / oldLen) * newLen).toInt().coerceIn(0, newLen - 1)
                        updateSelection(nextRow, mappedCol)
                    }
                    pendingKeyUpSwallow.add(keyCode)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (curRow < gridButtons.lastIndex) {
                        val oldLen = gridButtons[curRow].size
                        val nextRow = curRow + 1
                        val newLen = gridButtons[nextRow].size
                        val mappedCol = (((curCol + 0.5) / oldLen) * newLen).toInt().coerceIn(0, newLen - 1)
                        updateSelection(nextRow, mappedCol)
                    }
                    pendingKeyUpSwallow.add(keyCode)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    pendingKeyUpSwallow.add(keyCode)
                    val rows = currentRows()
                    if (curRow in rows.indices && curCol in rows[curRow].indices) {
                        val key = rows[curRow][curCol]
                        gridButtons.getOrNull(curRow)?.getOrNull(curCol)?.isPressed = true
                        if (event.repeatCount == 0 || key == "⌫") {
                            handleKeyPress(key)
                        }
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pendingKeyUpSwallow.remove(keyCode)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                gridButtons.getOrNull(curRow)?.getOrNull(curCol)?.isPressed = false
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun rebuildKeys(focusKeyTag: String? = null) {
        val container = keysContainer ?: return
        container.removeAllViews()
        gridButtons.clear()
        langBadge?.text = when (mode) {
            LayoutMode.RU -> if (shifted) "РУС (ЗАГЛ) • Web UTF-8 активен" else "РУС • Web UTF-8 активен"
            LayoutMode.EN -> if (shifted) "ENG (CAPS) • Web UTF-8 активен" else "ENG • Web UTF-8 активен"
            LayoutMode.SYM -> "СИМВОЛЫ"
        }

        val rows = currentRows()

        var targetRow = if (focusKeyTag == null) 0 else curRow
        var targetCol = if (focusKeyTag == null) 0 else curCol
        for (ri in rows.indices) {
            val row = rows[ri]
            val rowBtns = ArrayList<Button>(row.size)
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46)
                ).apply { bottomMargin = dp(6) }
            }
            for (ci in row.indices) {
                val k = row[ci]
                val label = when {
                    k == "Пробел" -> "Пробел"
                    k == "RU/EN" -> if (mode == LayoutMode.RU) "ENG" else "РУС"
                    shifted && k.length == 1 -> k.uppercase()
                    else -> k
                }
                val weight = when (k) {
                    "Пробел" -> 3.6f
                    "RU/EN", "123", "АБВ", "ABC", "⏎" -> 1.5f
                    else -> 1.0f
                }
                val btn = Button(this).apply {
                    text = label
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    background = makeKeyBg()
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(dp(4), 0, dp(4), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        weight
                    ).apply { marginEnd = dp(5) }
                    setOnClickListener {
                        updateSelection(ri, ci)
                        handleKeyPress(k)
                    }
                }
                if (focusKeyTag != null && focusKeyTag == k) {
                    targetRow = ri
                    targetCol = ci
                }
                rowBtns.add(btn)
                rowView.addView(btn)
            }
            gridButtons.add(rowBtns)
            container.addView(rowView)
        }
        mainHandler.post { updateSelection(targetRow, targetCol) }
    }

    private fun handleKeyPress(k: String) {
        val ic = currentInputConnection
        when (k) {
            "⇧" -> {
                shifted = !shifted
                rebuildKeys("⇧")
            }
            "RU/EN" -> {
                mode = if (mode == LayoutMode.RU) LayoutMode.EN else LayoutMode.RU
                rebuildKeys("RU/EN")
            }
            "АБВ" -> {
                mode = LayoutMode.RU
                rebuildKeys("RU/EN")
            }
            "ABC" -> {
                mode = LayoutMode.EN
                rebuildKeys("RU/EN")
            }
            "123" -> {
                mode = LayoutMode.SYM
                rebuildKeys("АБВ")
            }
            "⌫" -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            "Пробел" -> {
                ic?.commitText(" ", 1)
            }
            "⏎" -> {
                val handled = sendDefaultEditorAction(true)
                if (!handled) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
                requestHideSelf(0)
            }
            else -> {
                val out = if (shifted && k.length == 1) k.uppercase() else k
                ic?.commitText(out, 1)
                if (shifted) {
                    shifted = false
                    rebuildKeys(k)
                }
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val UDP_PORT = 8085
    }
}
