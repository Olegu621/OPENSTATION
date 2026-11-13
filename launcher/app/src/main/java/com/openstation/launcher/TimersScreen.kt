package com.openstation.launcher

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** "04:59" or "1:04:59". */
fun formatDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

/** Remaining seconds of a daemon timer object, extrapolated from the poll time. */
fun timerRemaining(t: JSONObject, fetchedAtMs: Long): Int =
    t.optInt("remainingSec", 0) - ((SystemClock.elapsedRealtime() - fetchedAtMs) / 1000L).toInt()

/**
 * TimersTv.dc.html: active timers + alarms on the left, a D-pad editor (two up/down number
 * columns) for a new timer or alarm on the right. Talks to the daemon through [post].
 */
class TimersScreen(
    context: Context,
    private val post: (path: String, body: String) -> Unit
) : FrameLayout(context) {

    private val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val sounds = listOf("chime" to "Сигнал", "wave" to "Моя волна", "calm" to "Спокойная волна")

    private var timers = JSONArray()
    private var fetchedAt = 0L
    private var alarms = JSONArray()
    private var listKey = "-"
    private val timerRows = ArrayList<Triple<JSONObject, TextView, ProgressBar>>()
    private val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var isAlarm = false
    private val kindBtns = ArrayList<TextView>()
    private val left = Picker(0, 99, 10)
    private val right = Picker(0, 59, 0)
    private val tvUnit = context.label("мин", 18f, Theme.alpha(Theme.TEXT, 0.6f))
    private val days = BooleanArray(7) { it < 5 }
    private val dayBtns = ArrayList<TextView>()
    private val daysRow = LinearLayout(context)
    private var soundIdx = 1
    private val tvSoundValue = context.label("", 17f, Theme.TEXT, 600)
    private val soundRow = LinearLayout(context)
    private val btnSave: TextView

    init {
        background = Theme.glowBackground(0.8f, 0.3f, 0.4f)
        val c = context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(56), c.dp(48), c.dp(56), c.dp(48))
        }
        addView(root, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- Left: active timers and alarms ----
        val leftCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        leftCol.addView(c.label("Таймеры и будильники", 34f, Theme.TEXT, 700), LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = c.dp(22)
        })
        leftCol.addView(ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(c.dp(4), c.dp(4), c.dp(4), c.dp(4))
            addView(listCol)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        leftCol.addView(c.label("OK — вкл/выкл · долгое нажатие — удалить · «Назад» — выход", 15f, Theme.alpha(Theme.TEXT, 0.55f)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = c.dp(12) })
        root.addView(leftCol, LinearLayout.LayoutParams(c.dp(520), -1).apply { marginEnd = c.dp(40) })

        // ---- Right: editor ----
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(32), c.dp(32), c.dp(32), c.dp(32))
            background = Theme.rounded(Color.argb(140, 12, 12, 18), c.dpf(28))
        }
        val kinds = LinearLayout(c).apply {
            setPadding(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
            background = Theme.rounded(Theme.ROW, c.dpf(26))
        }
        listOf("Таймер", "Будильник").forEachIndexed { i, txt ->
            val b = c.label(txt, 17f, Theme.TEXT, 700).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnClickListener { setKind(i == 1) }
            }
            kindBtns.add(b)
            kinds.addView(b, LinearLayout.LayoutParams(0, c.dp(44), 1f).apply { if (i == 0) marginEnd = c.dp(6) })
        }
        card.addView(kinds)

        val pickRow = LinearLayout(c).apply { gravity = Gravity.CENTER }
        pickRow.addView(left.view)
        pickRow.addView(c.label(":", 80f, Theme.alpha(Theme.TEXT, 0.6f), 300), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = c.dp(18); marginEnd = c.dp(18)
        })
        pickRow.addView(right.view)
        pickRow.addView(tvUnit, LinearLayout.LayoutParams(c.dp(60), -2).apply { marginStart = c.dp(18) })
        card.addView(pickRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(20) })

        dayNames.forEachIndexed { i, d ->
            val b = c.label(d, 16f, Theme.TEXT, 700).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnClickListener { days[i] = !days[i]; renderDays() }
            }
            dayBtns.add(b)
            daysRow.addView(b, LinearLayout.LayoutParams(0, c.dp(48), 1f).apply { if (i < 6) marginEnd = c.dp(8) })
        }
        card.addView(daysRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(20) })

        soundRow.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(16), 0, c.dp(16), 0)
            background = Theme.focusable(Color.TRANSPARENT, c.dpf(16), c.dp(3))
            isFocusable = true
            isClickable = true
            setOnClickListener { soundIdx = (soundIdx + 1) % sounds.size; renderSound() }
            addView(c.label("Звук", 17f, Theme.alpha(Theme.TEXT, 0.7f)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tvSoundValue)
        }
        card.addView(soundRow, LinearLayout.LayoutParams(-1, c.dp(48)).apply { topMargin = c.dp(16) })

        card.addView(View(c), LinearLayout.LayoutParams(-1, 0, 1f))
        val btnRow = LinearLayout(c).apply { gravity = Gravity.END }
        btnRow.addView(c.pillButton("Сбросить", primary = false) { resetEditor() })
        btnRow.addView(View(c), LinearLayout.LayoutParams(c.dp(12), 1))
        btnSave = c.pillButton("", primary = true) { save() }
        btnRow.addView(btnSave)
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, LinearLayout.LayoutParams(0, -1, 1f))

        setKind(false)
        renderSound()
    }

    fun focusFirst() {
        left.view.requestFocus()
    }

    /** Fresh data from /api/status. Rebuilds the list only when its structure changes. */
    fun update(newTimers: JSONArray?, timersFetchedAt: Long, newAlarms: JSONArray?) {
        timers = newTimers ?: JSONArray()
        fetchedAt = timersFetchedAt
        alarms = newAlarms ?: JSONArray()
        val key = buildString {
            for (i in 0 until timers.length()) append(timers.optJSONObject(i)?.optInt("id")).append(',')
            append('|').append(alarms.toString())
        }
        if (key != listKey) {
            listKey = key
            rebuildList()
        } else {
            tick()
        }
    }

    fun tick() {
        for ((t, tv, pb) in timerRows) {
            val rem = timerRemaining(t, fetchedAt).coerceAtLeast(0)
            val total = t.optInt("totalSec", 1).coerceAtLeast(1)
            tv.text = formatDuration(rem)
            pb.progress = (rem * 1000L / total).toInt()
        }
    }

    private fun rebuildList() {
        val focusedIdx = (0 until listCol.childCount).indexOfFirst { listCol.getChildAt(it).hasFocus() }
        listCol.removeAllViews()
        timerRows.clear()
        val c = context
        for (i in 0 until timers.length()) {
            val t = timers.optJSONObject(i) ?: continue
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(c.dp(20), c.dp(18), c.dp(20), c.dp(18))
                background = Theme.rounded(Theme.alpha(Theme.accent, 0.2f), c.dpf(22))
            }
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val tvRem = c.label("", 30f, Theme.TEXT, 700)
            val name = t.optString("label").ifBlank { "Таймер" }
            col.addView(tvRem)
            col.addView(c.label("Таймер «$name» · из ${formatDuration(t.optInt("totalSec"))}", 15f, Theme.alpha(Theme.TEXT, 0.72f)),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = c.dp(4) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            val id = t.optInt("id")
            row.addView(c.pillButton("Отменить", primary = false, heightDp = 44) {
                post("/api/timers/cancel", JSONObject().put("id", id).toString())
            })
            box.addView(row)
            val pb = c.accentProgress(5)
            box.addView(pb, LinearLayout.LayoutParams(-1, c.dp(5)).apply { topMargin = c.dp(10) })
            timerRows.add(Triple(t, tvRem, pb))
            listCol.addView(box, itemLp())
        }
        for (i in 0 until alarms.length()) {
            val a = alarms.optJSONObject(i) ?: continue
            val on = a.optBoolean("enabled", true)
            val row = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(20), c.dp(16), c.dp(20), c.dp(16))
                background = Theme.focusable(Theme.SURFACE, c.dpf(22), c.dp(3))
                isFocusable = true
                isClickable = true
                setOnClickListener { setAlarmEnabled(i, !on) }
                setOnLongClickListener { confirmDelete(i); true }
            }
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(c.label(a.optString("time"), 30f, if (on) Theme.TEXT else Theme.alpha(Theme.TEXT, 0.45f), 700))
            col.addView(c.label(alarmSubtitle(a), 15f, Theme.alpha(Theme.TEXT, 0.65f)), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = c.dp(4)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(SwitchPill(c).apply { checked = on })
            listCol.addView(row, itemLp())
        }
        if (listCol.childCount == 0) {
            listCol.addView(c.label("Нет таймеров и будильников.\nСоздайте их справа или скажите «Алиса, поставь таймер».",
                17f, Theme.TEXT_FAINT, lines = 3).apply { setLineSpacing(0f, 1.3f) })
        }
        tick()
        // Up/Down are taken by the number columns, so with an empty list "left" leads to the kind toggle.
        val listFocusable = listCol.getFocusables(View.FOCUS_DOWN).isNotEmpty()
        left.view.nextFocusLeftId = if (listFocusable) View.NO_ID else kindBtns[0].id
        if (focusedIdx >= 0) {
            val target = listCol.getChildAt(focusedIdx.coerceAtMost(listCol.childCount - 1))
            val focusable = target?.getFocusables(View.FOCUS_DOWN)?.firstOrNull()
            if (focusable != null) focusable.requestFocus() else left.view.requestFocus()
        }
    }

    private fun itemLp() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(14) }

    private fun alarmSubtitle(a: JSONObject): String {
        val d = a.optJSONArray("days")
        val on = BooleanArray(7) { d?.optBoolean(it, false) ?: false }
        val daysTxt = when {
            on.all { it } -> "ежедневно"
            on.none { it } -> "однократно"
            on.take(5).all { it } && !on[5] && !on[6] -> "пн–пт"
            !on.take(5).any { it } && on[5] && on[6] -> "сб, вс"
            else -> dayNames.filterIndexed { i, _ -> on[i] }.joinToString(", ") { it.lowercase() }
        }
        val sound = sounds.firstOrNull { it.first == a.optString("sound") }?.second ?: "Сигнал"
        val name = a.optString("label").ifBlank { "Будильник" }
        return "$name · $daysTxt · $sound"
    }

    private fun postAlarms(arr: JSONArray) {
        alarms = arr
        listKey = "-"
        update(timers, fetchedAt, arr)
        post("/api/alarms", JSONObject().put("alarms", arr).toString())
    }

    private fun copyAlarms(skip: Int = -1): JSONArray {
        val out = JSONArray()
        for (i in 0 until alarms.length()) if (i != skip) out.put(JSONObject(alarms.optJSONObject(i)?.toString() ?: "{}"))
        return out
    }

    private fun setAlarmEnabled(idx: Int, enabled: Boolean) {
        val arr = copyAlarms()
        arr.optJSONObject(idx)?.put("enabled", enabled)
        postAlarms(arr)
    }

    private fun confirmDelete(idx: Int) {
        val a = alarms.optJSONObject(idx) ?: return
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Удалить будильник ${a.optString("time")}?")
            .setPositiveButton("Удалить") { _, _ -> postAlarms(copyAlarms(skip = idx)) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setKind(alarm: Boolean) {
        isAlarm = alarm
        kindBtns.forEachIndexed { i, b ->
            val sel = (i == 1) == alarm
            b.background = Theme.focusable(
                if (sel) Theme.TEXT else Color.TRANSPARENT, context.dpf(22), context.dp(3),
                Theme.accent, focusedFill = if (sel) Theme.TEXT else Theme.FOCUS_FILL
            )
            b.setTextColor(if (sel) Theme.ON_ACCENT else Theme.TEXT)
        }
        resetEditor()
        daysRow.visibility = if (alarm) View.VISIBLE else View.GONE
        soundRow.visibility = if (alarm) View.VISIBLE else View.GONE
        tvUnit.text = if (alarm) "" else "мин"
        btnSave.text = if (alarm) "Сохранить будильник" else "Запустить таймер"
        renderDays()
    }

    private fun resetEditor() {
        if (isAlarm) {
            left.set(0, 23, 7)
            right.set(0, 59, 30)
        } else {
            left.set(0, 99, 10)
            right.set(0, 59, 0)
        }
    }

    private fun renderDays() {
        dayBtns.forEachIndexed { i, b ->
            b.background = Theme.focusable(
                if (days[i]) Theme.accent else Theme.alpha(Color.WHITE, 0.08f), context.dpf(16), context.dp(3),
                Color.WHITE, focusedFill = if (days[i]) Theme.accent else Theme.FOCUS_FILL
            )
            b.setTextColor(if (days[i]) Theme.ON_ACCENT else Theme.TEXT)
        }
    }

    private fun renderSound() {
        tvSoundValue.text = "${sounds[soundIdx].second} ›"
    }

    private fun save() {
        if (isAlarm) {
            val o = JSONObject()
                .put("time", String.format(Locale.US, "%02d:%02d", left.value, right.value))
                .put("days", JSONArray().apply { days.forEach { put(it) } })
                .put("label", "")
                .put("enabled", true)
                .put("sound", sounds[soundIdx].first)
            postAlarms(copyAlarms().put(o))
            Toast.makeText(context, "Будильник на ${o.optString("time")} сохранён", Toast.LENGTH_SHORT).show()
        } else {
            val sec = left.value * 60 + right.value
            if (sec < 1) {
                Toast.makeText(context, "Укажите время таймера", Toast.LENGTH_SHORT).show()
                return
            }
            post("/api/timers", JSONObject().put("seconds", sec).put("label", "").toString())
            Toast.makeText(context, "Таймер на ${formatDuration(sec)} запущен", Toast.LENGTH_SHORT).show()
        }
    }

    /** Focusable number column: D-pad up/down changes the value (wraps around). */
    private inner class Picker(private var min: Int, private var max: Int, var value: Int) {
        private val tv = context.label("", 96f, Theme.TEXT, 300).apply { gravity = Gravity.CENTER }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(10), 0, context.dp(10))
            background = Theme.focusable(Color.TRANSPARENT, context.dpf(24), context.dp(3), focusedFill = Theme.ROW)
            isFocusable = true
            isClickable = true
            addView(context.label("▲", 18f, Theme.TEXT_FAINT).apply { gravity = Gravity.CENTER })
            addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6); bottomMargin = context.dp(6) })
            addView(context.label("▼", 18f, Theme.TEXT_FAINT).apply { gravity = Gravity.CENTER })
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { step(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { step(-1); true }
                    else -> false
                }
            }
            setOnClickListener { step(1) }
            layoutParams = LinearLayout.LayoutParams(context.dp(150), -2)
        }

        fun set(min: Int, max: Int, value: Int) {
            this.min = min
            this.max = max
            this.value = value
            render()
        }

        private fun step(d: Int) {
            val span = max - min + 1
            value = ((value - min + d) % span + span) % span + min
            render()
        }

        private fun render() {
            tv.text = String.format(Locale.US, "%02d", value)
        }
    }
}
