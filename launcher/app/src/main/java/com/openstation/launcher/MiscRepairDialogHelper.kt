package com.openstation.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Interactive non-automatic Wi-Fi / Bluetooth (misc revision) repair popup for Android TV.
 * Shown when daemon reports `miscRepair.showPopup == true`, allowing the user to pick
 * between revision `0008_00052` (Intel) and `00053` (Realtek/Unisoc) and explicitly confirm
 * flashing `/dev/block/.../misc` with immediate reboot.
 */
internal class MiscRepairDialogHelper(
    private val activity: Activity,
    private val handler: Handler
) {
    private var dialog: AlertDialog? = null
    private var tvDiag: TextView? = null
    private var card0008: LinearLayout? = null
    private var card00053: LinearLayout? = null
    private var badge0008: TextView? = null
    private var badge00053: TextView? = null
    private var tvStatus: TextView? = null
    private var btnFlash: TextView? = null
    private var btnDismiss: TextView? = null

    private var selectedRev: String = "0008_00052"
    private var flashing: Boolean = false
    private var dismissedInSession: Boolean = false
    private var lastState: JSONObject? = null

    fun handleDaemonState(mr: JSONObject?) {
        if (mr == null) return
        lastState = mr
        val showPopup = mr.optBoolean("showPopup", false)
        val serverFlashing = mr.optBoolean("flashing", false)
        val serverDismissed = mr.optBoolean("dismissed", false)
        if (serverDismissed) dismissedInSession = true

        if (dialog?.isShowing == true) {
            bindState(mr)
            return
        }

        if (showPopup && !dismissedInSession && !flashing && !serverFlashing && !activity.isFinishing && !activity.isDestroyed) {
            val rec = mr.optString("recommendedRevision", "").trim()
            val cur = mr.optString("currentRevision", "").trim()
            selectedRev = when {
                rec == "0008_00052" || rec == "00053" -> rec
                cur == "0008_00052" || cur == "00053" -> cur
                else -> "0008_00052"
            }
            showDialog(mr)
        }
    }

    fun showManual() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog?.isShowing == true) return
        thread(isDaemon = true) {
            var fetched: JSONObject? = null
            try {
                val conn = (URL("http://127.0.0.1:8080/api/misc/status").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1500
                    readTimeout = 2000
                }
                if (conn.responseCode in 200..299) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    fetched = JSONObject(raw)
                }
                conn.disconnect()
            } catch (_: Throwable) {}

            val mr = fetched ?: lastState ?: JSONObject()
            handler.post {
                if (activity.isFinishing || activity.isDestroyed || dialog?.isShowing == true) return@post
                lastState = mr
                val rec = mr.optString("recommendedRevision", "").trim()
                val cur = mr.optString("currentRevision", "").trim()
                selectedRev = when {
                    rec == "0008_00052" || rec == "00053" -> rec
                    cur == "0008_00052" || cur == "00053" -> cur
                    else -> "0008_00052"
                }
                showDialog(mr)
            }
        }
    }

    fun dismissIfShowing() {
        try {
            if (dialog?.isShowing == true) dialog?.dismiss()
        } catch (_: Throwable) {}
        dialog = null
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun revLabel(rev: String): String = when (rev) {
        "0008_00052" -> "0008 / 00052 (Intel)"
        "00053" -> "00053 (Realtek / Unisoc)"
        else -> "не определена"
    }

    private fun showDialog(mr: JSONObject) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF0B1220"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(20).toFloat()
            }
        }

        root.addView(TextView(activity).apply {
            text = "НЕ РАБОТАЕТ WI-FI И BLUETOOTH?"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        })
        root.addView(TextView(activity).apply {
            text = "Выберите ревизию вашей Яндекс Станции Макс — будет прошит правильный раздел misc."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        tvDiag = TextView(activity).apply {
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1EF59E0B"))
                setStroke(dp(1), Color.parseColor("#55F59E0B"))
                cornerRadius = dp(10).toFloat()
            }
        }
        root.addView(tvDiag)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val (c1, b1) = makeRevisionCard(
            title = "Ревизия 0008 / 00052",
            subtitle = "Intel Wi-Fi/BT · DT rev35f\nРанние и основные платы",
            rev = "0008_00052"
        )
        val (c2, b2) = makeRevisionCard(
            title = "Ревизия 00053",
            subtitle = "Realtek / Unisoc · DT rev053\nПоздние ревизии платы",
            rev = "00053"
        )
        card0008 = c1; badge0008 = b1
        card00053 = c2; badge00053 = b2

        c1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        c2.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
        row.addView(c1)
        row.addView(c2)
        root.addView(row)

        root.addView(TextView(activity).apply {
            text = "⚠ После подтверждения раздел misc будет перезаписан и станция немедленно перезагрузится."
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        })

        tvStatus = TextView(activity).apply {
            text = ""
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12.5f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(tvStatus)

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

        btnDismiss = makeActionButton("Позже", accent = false) {
            dismissOnServer()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
        }
        btnFlash = makeActionButton("⚡ Прошить и перезагрузить", accent = true) {
            confirmFlashSelected()
        }
        footer.addView(btnDismiss)
        footer.addView(btnFlash)
        root.addView(footer)

        bindState(mr)
        val dlg = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false)
            .create()
        dialog = dlg
        dlg.show()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        c1.post {
            if (selectedRev == "00053") c2.requestFocus() else c1.requestFocus()
        }
    }

    private fun makeRevisionCard(title: String, subtitle: String, rev: String): Pair<LinearLayout, TextView> {
        val badge = TextView(activity).apply {
            text = "Рекомендуется"
            setTextColor(Color.parseColor("#0B0F17"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(999).toFloat()
            }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(activity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(badge)
            addView(top)
            addView(TextView(activity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11.5f
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener {
                if (!flashing) {
                    selectedRev = rev
                    updateSelectionStyles()
                }
            }
            setOnFocusChangeListener { _, _ -> updateSelectionStyles() }
        }
        return card to badge
    }

    private fun makeActionButton(label: String, accent: Boolean, onClick: () -> Unit): TextView {
        val normBg = if (accent) Color.parseColor("#FF0284C7") else Color.parseColor("#24FFFFFF")
        val focBg = if (accent) Color.parseColor("#FF38BDF8") else Color.parseColor("#44FFFFFF")
        return TextView(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            fun updateBg(focused: Boolean) {
                background = GradientDrawable().apply {
                    setColor(if (focused) focBg else normBg)
                    setStroke(dp(if (focused) 2 else 1), Color.parseColor(if (focused) "#FFFFFFFF" else "#33FFFFFF"))
                    cornerRadius = dp(12).toFloat()
                }
            }
            updateBg(false)
            setOnFocusChangeListener { _, hasFocus -> updateBg(hasFocus) }
            setOnClickListener { onClick() }
        }
    }

    private fun updateSelectionStyles() {
        fun styleCard(card: LinearLayout?, rev: String) {
            card ?: return
            val sel = (selectedRev == rev)
            val foc = card.hasFocus()
            card.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (sel) "#2800D4FF" else "#12FFFFFF"))
                setStroke(
                    dp(if (sel || foc) 2 else 1),
                    Color.parseColor(
                        when {
                            foc && sel -> "#FFFFFFFF"
                            sel -> "#FF00D4FF"
                            foc -> "#AAFFFFFF"
                            else -> "#33FFFFFF"
                        }
                    )
                )
                cornerRadius = dp(14).toFloat()
            }
        }
        styleCard(card0008, "0008_00052")
        styleCard(card00053, "00053")
    }

    private fun bindState(mr: JSONObject) {
        val wifiOk = mr.optBoolean("wifiOk", false)
        val btOk = mr.optBoolean("btOk", false)
        val dt = mr.optString("currentDtId", "?").ifBlank { "?" }
        val chip = when (mr.optString("chip", "unknown")) {
            "intel" -> "Intel 7265"
            "realtek" -> "Realtek 8822ES"
            "unisoc" -> "Unisoc UWE5621"
            else -> "не определён"
        }
        val rec = mr.optString("recommendedRevision", "")
        tvDiag?.text = "Wi-Fi: ${if (wifiOk) "OK" else "нет"} · BT: ${if (btOk) "OK" else "нет"} · DT: $dt · Чип: $chip" +
            if (rec.isNotEmpty()) " · Рекомендуется: ${revLabel(rec)}" else ""
        badge0008?.visibility = if (rec == "0008_00052") android.view.View.VISIBLE else android.view.View.GONE
        badge00053?.visibility = if (rec == "00053") android.view.View.VISIBLE else android.view.View.GONE
        updateSelectionStyles()
    }

    private fun confirmFlashSelected() {
        if (flashing) return
        flashing = true
        btnFlash?.isEnabled = false
        btnDismiss?.isEnabled = false
        tvStatus?.setTextColor(Color.parseColor("#FDE68A"))
        tvStatus?.text = "⏳ Прошивка misc (${revLabel(selectedRev)}) и перезагрузка..."

        thread(isDaemon = true) {
            var ok = false
            var err = ""
            try {
                val payload = JSONObject()
                    .put("revision", selectedRev)
                    .put("confirm", true)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val conn = (URL("http://127.0.0.1:8080/api/misc/flash").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 2000
                    readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val body = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) { "" }
                conn.disconnect()
                if (code == 200) {
                    ok = true
                } else {
                    err = try { JSONObject(body).optString("error", "HTTP $code") } catch (_: Throwable) { "HTTP $code" }
                }
            } catch (t: Throwable) {
                err = t.message ?: "Ошибка сети"
            }

            handler.post {
                if (ok) {
                    tvStatus?.setTextColor(Color.parseColor("#6EE7B7"))
                    tvStatus?.text = "✓ misc прошит (${revLabel(selectedRev)}). Станция перезагружается..."
                } else {
                    flashing = false
                    btnFlash?.isEnabled = true
                    btnDismiss?.isEnabled = true
                    tvStatus?.setTextColor(Color.parseColor("#FCA5A5"))
                    tvStatus?.text = "✗ Ошибка прошивки: $err"
                }
            }
        }
    }

    private fun dismissOnServer() {
        dismissedInSession = true
        dismissIfShowing()
        thread(isDaemon = true) {
            try {
                val conn = (URL("http://127.0.0.1:8080/api/misc/dismiss").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 900
                    readTimeout = 900
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) {}
        }
    }
}
