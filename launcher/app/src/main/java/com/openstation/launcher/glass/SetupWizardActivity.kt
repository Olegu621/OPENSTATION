package com.openstation.launcher.glass

import com.openstation.launcher.*
import com.openstation.launcher.R

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class SetupWizardActivity : Activity() {

    companion object {
        const val PREFS_NAME = "openstation_launcher_prefs"
        const val KEY_WIZARD_DISMISSED_VER = "wizard_dismissed_ver"
        const val CURRENT_WIZARD_VER = 1
        const val EXTRA_MANUAL = "manual"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = 1
    private var manualLaunch = false
    private var initialSetupCompleted: Boolean? = null
    private var lastQrUrl = ""
    private var lastKwsDetections = -1

    // Header & Step pills
    private lateinit var stepPills: Array<TextView>
    private lateinit var stepContents: Array<ScrollView>
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnFinish: LinearLayout
    private lateinit var tvStepCounter: TextView

    // Left panel: Orb + Checklist + QR
    private lateinit var wizardOrb: AliceOrbView
    private lateinit var tvOrbCaption: TextView
    private lateinit var tvChkDaemon: TextView
    private lateinit var tvChkDns: TextView
    private lateinit var tvChkGemini: TextView
    private lateinit var tvChkKws: TextView
    private lateinit var tvChkMusic: TextView
    private lateinit var ivQr: ImageView
    private lateinit var tvQrUrl: TextView

    // Step 1: Diagnostics
    private lateinit var tvDiagIp: TextView
    private lateinit var tvDiagDns: TextView
    private lateinit var tvDiagAudio: TextView
    private lateinit var tvDiagHw: TextView
    private lateinit var tvStep1RemoteStatus: TextView
    private var bleAutoStarted = false

    // Step 2: Alice / Gemini Live
    private lateinit var tvStep2GeminiStatus: TextView
    private lateinit var tvStep2KwsLive: TextView
    private lateinit var vStep2VuContainer: LinearLayout
    private lateinit var vStep2VuBarFill: View
    private lateinit var tvStep2VuStats: TextView
    private var curAudioRms = 0
    private var curAudioNoise = 0
    private var curAudioBackend = "aaudio_1ch_16k_vqe"
    private val voicePills = LinkedHashMap<String, TextView>()
    private val proxyPills = LinkedHashMap<String, TextView>()
    private val promptPills = LinkedHashMap<String, TextView>()

    // Step 3: Yandex Music & Audio
    private lateinit var tvStep3MusicStatus: TextView
    private val routePills = LinkedHashMap<String, TextView>()
    private val eqPills = LinkedHashMap<String, TextView>()

    // Step 4: LEDs & Hardware & CEC
    private val ringPills = LinkedHashMap<String, TextView>()
    private val actionPills = LinkedHashMap<String, TextView>()
    private lateinit var btnAutoBri: TextView

    // Step 5: Summary
    private lateinit var tvStep5Summary: TextView

    // Cached daemon config state
    private var curIp = "127.0.0.1"
    private var curHostname = "station-max"
    private var curFwVersion = "1.2.3"
    private var curTempC = 0.0
    private var curFb5 = false
    private var curDnsMode = ""
    private var curDnsHost = ""
    private var curHasKey = false
    private var curVoice = "Aoede"
    private var curProxyHost = "ai-pool.comss.one"
    private var curWakeEn = true
    private var curKwsReady = false
    private var curKwsDetections = 0
    private var curRoute = "auto"
    private var curEq = "balanced"
    private var curRingMode = "music"
    private var curAutoBri = true
    private var curActShort = "assistant"
    private var curMusicLoggedIn = false
    private var curMusicUser = ""
    private var curMusicPlus = false
    private var activePromptPreset = "universal"

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDaemonStatus()
            handler.postDelayed(this, 1200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        manualLaunch = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        StationAudioService.ensureStarted(this)

        buildUi()
        updateStepUi(focusContent = true)
        updateQrCode(detectLocalIp())
        pollDaemonStatus()
        if (!manualLaunch && !bleAutoStarted) {
            bleAutoStarted = true
            handler.postDelayed({ startRemoteAutoPair() }, 1800L)
        }
    }

    override fun onResume() {
        super.onResume()
        StationAudioService.ensureStarted(this)
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            if (currentStep > 1) {
                currentStep--
                updateStepUi(focusContent = true)
                return true
            }
            dismissWizardAndFinish(markSkipped = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun dismissWizardAndFinish(markSkipped: Boolean) {
        if (markSkipped) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_WIZARD_DISMISSED_VER, CURRENT_WIZARD_VER)
                .apply()
        }
        finish()
    }

    private fun buildUi() {
        val rootFrame = FrameLayout(this)
        val ivBg = ImageView(this).apply {
            setImageResource(R.drawable.bg_tv_stage)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#66040812"),
                    Color.parseColor("#38040812"),
                    Color.parseColor("#88040812")
                )
            )
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootFrame.addView(ivBg)
        rootFrame.addView(scrim)

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootFrame.addView(shell)

        // ===== HEADER =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = "OpenStation Max · Мастер настройки"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.03f
        }
        val tvSub = TextView(this).apply {
            text = "Автономная работа прямо на колонке (без ПК)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)

        val stepLabels = arrayOf("1. Старт", "2. Алиса", "3. Музыка", "4. Свет", "5. Готово")
        val stepsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#990B1220"))
                setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        stepPills = Array(5) { idx ->
            TextView(this).apply {
                text = stepLabels[idx]
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(4), dp(10), dp(4))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    currentStep = idx + 1
                    updateStepUi(focusContent = true)
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < 4) marginEnd = dp(3)
                }
            }.also { stepsRow.addView(it) }
        }

        val btnSkip = makeActionPill("Пропустить") {
            dismissWizardAndFinish(markSkipped = true)
        }

        header.addView(titleCol)
        header.addView(stepsRow)
        header.addView(btnSkip)
        shell.addView(header)

        // ===== BODY SPLIT =====
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // --- LEFT STATUS & PHONE QR CARD ---
        val leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#D40E172B"),
                    Color.parseColor("#E0080D1A")
                )
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#4400D4FF"))
            }
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.37f).apply {
                marginEnd = dp(14)
            }
        }

        val orbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        wizardOrb = AliceOrbView(this).apply {
            compactGlowBoost = 0.85f
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                marginEnd = dp(12)
            }
        }
        val orbTxtCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvOrbTitle = TextView(this).apply {
            text = "ЯНДЕКС СТАНЦИЯ МАКС"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvOrbCaption = TextView(this).apply {
            text = "Алиса и сервер готовы"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
        }
        orbTxtCol.addView(tvOrbTitle)
        orbTxtCol.addView(tvOrbCaption)
        orbRow.addView(wizardOrb)
        orbRow.addView(orbTxtCol)
        leftCard.addView(orbRow)

        val chkBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tvChkDaemon = makeCheckRow("● Службы станции")
        tvChkDns = makeCheckRow("● Private DNS (dns.comss.one)")
        tvChkGemini = makeCheckRow("● Ключ Gemini Live API")
        tvChkKws = makeCheckRow("● Офлайн фраза «Алиса»")
        tvChkMusic = makeCheckRow("● Яндекс Музыка (опц.)")
        listOf(tvChkDaemon, tvChkDns, tvChkGemini, tvChkKws, tvChkMusic).forEach { chkBox.addView(it) }
        leftCard.addView(chkBox)

        // Companion Phone QR Box
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA0D1628"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(14).toFloat()
            }
        }
        val qrWhite = LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        ivQr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrWhite.addView(ivQr)
        val qrTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvQrHead = TextView(this).apply {
            text = "Мастер на телефоне"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvQrUrl = TextView(this).apply {
            text = "http://127.0.0.1:8080/#wizard"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvQrNote = TextView(this).apply {
            text = "Сканируйте QR — удобно вставлять ключи со смартфона без ПК"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            setPadding(0, dp(2), 0, 0)
        }
        qrTextCol.addView(tvQrHead)
        qrTextCol.addView(tvQrUrl)
        qrTextCol.addView(tvQrNote)
        qrCard.addView(qrWhite)
        qrCard.addView(qrTextCol)
        leftCard.addView(qrCard)

        // --- RIGHT WIZARD STEPS STAGE + FOOTER ---
        val rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#D00E1629"),
                    Color.parseColor("#DC090F1E")
                )
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#38FFFFFF"))
            }
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.63f)
        }

        val stepStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        stepContents = arrayOf(
            buildStep1Pane(),
            buildStep2Pane(),
            buildStep3Pane(),
            buildStep4Pane(),
            buildStep5Pane()
        )
        stepContents.forEach { stepStage.addView(it) }
        rightCard.addView(stepStage)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        btnPrev = makeActionPill("← Назад") {
            if (currentStep > 1) {
                currentStep--
                updateStepUi(focusContent = true)
            }
        }
        tvStepCounter = TextView(this).apply {
            text = "Шаг 1 из 5"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnNext = makeActionPill("Далее →", goldHighlight = true) {
            if (currentStep < 5) {
                currentStep++
                updateStepUi(focusContent = true)
            }
        }
        btnFinish = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundResource(R.drawable.glass_bg_pill_gold)
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
            }
            setOnClickListener { completeSetupAndClose() }
            val tv = TextView(this@SetupWizardActivity).apply {
                text = "✨ Завершить настройку"
                setTextColor(Color.parseColor("#0B0F17"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tv)
        }
        footer.addView(btnPrev)
        footer.addView(tvStepCounter)
        footer.addView(btnNext)
        footer.addView(btnFinish)
        rightCard.addView(footer)

        bodyRow.addView(leftCard)
        bodyRow.addView(rightCard)
        shell.addView(bodyRow)

        setContentView(rootFrame)
    }

    private fun wrapStepScroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(content)
    }

    private fun buildStep1Pane(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(makePaneHeading(
            "Шаг 1 · Добро пожаловать в OpenStation Max",
            "Все сервисы работают локально внутри колонки — после прошивки компьютер больше не нужен."
        ))

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(8))
        }
        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        tvDiagIp = TextView(this)
        tvDiagDns = TextView(this)
        r1.addView(makeDiagCard("Сеть и Веб-пульт", tvDiagIp, marginEndDp = 8))
        r1.addView(makeDiagCard("Private DNS (Gemini)", tvDiagDns, marginEndDp = 0))

        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvDiagAudio = TextView(this)
        tvDiagHw = TextView(this)
        r2.addView(makeDiagCard("Аудио и Микрофоны", tvDiagAudio, marginEndDp = 8))
        r2.addView(makeDiagCard("Оборудование Станции", tvDiagHw, marginEndDp = 0))

        grid.addView(r1)
        grid.addView(r2)
        col.addView(grid)

        tvStep1RemoteStatus = TextView(this).apply {
            text = "🎮 BLE-пульт YNDX: ожидание автопоиска..."
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A0C192E"))
                setStroke(dp(1), Color.parseColor("#5500D4FF"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        col.addView(tvStep1RemoteStatus)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        btnRow.addView(makeActionPill("🔍 Найти BLE-пульт") {
            startRemoteAutoPair()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        })
        btnRow.addView(makeActionPill("🔔 Найти (Зуммер)") {
            triggerRemoteBuzz()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        })
        btnRow.addView(makeActionPill("📶 Wi-Fi") {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                Toast.makeText(this, "Настройки Wi-Fi недоступны", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        })
        btnRow.addView(makeActionPill("🌐 DNS comss.one") {
            applyPrivateDns("hostname", "dns.comss.one")
        })
        col.addView(btnRow)

        val tip = TextView(this).apply {
            text = "Совет: наведите камеру телефона на QR-код слева — откроется точно такой же мастер настройки прямо в браузере."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(tip)
        return wrapStepScroll(col)
    }

    private fun buildStep2Pane(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(makePaneHeading(
            "Шаг 2 · Голосовой ассистент «Алиса» (Gemini Live)",
            "Офлайн-распознавание фразы «Алиса» + двусторонний голосовой ИИ без облака Яндекса."
        ))

        tvStep2GeminiStatus = TextView(this).apply {
            text = "Проверка ключа Gemini Live..."
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 12.5f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#24F59E0B"))
                setStroke(dp(1), Color.parseColor("#55F59E0B"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        col.addView(tvStep2GeminiStatus)

        val keyBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        keyBtns.addView(makeActionPill("🔑 Ввести ключ с пульта") {
            openGeminiKeyDialog()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        keyBtns.addView(makeActionPill("⚡ Проверить Gemini Live", goldHighlight = true) {
            testGeminiLiveConnection()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        keyBtns.addView(makeActionPill("🎤 Разбудить Алису") {
            postAsync("http://127.0.0.1:8080/api/assistant/trigger", """{"reason":"wizard"}""")
            Toast.makeText(this, "Алиса слушает...", Toast.LENGTH_SHORT).show()
        })
        col.addView(keyBtns)

        col.addView(makeSubLabel("Голос ответа (Gemini TTS)"))
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        listOf(
            "Aoede" to "Aoede (мягкий)",
            "Kore" to "Kore (яркий)",
            "Leda" to "Leda (живой)",
            "Puck" to "Puck (муж.)",
            "Charon" to "Charon (глуб.)"
        ).forEach { (id, label) ->
            val p = makeChoicePill(label) {
                curVoice = id
                pushConfigPatch(JSONObject().put("assistant", JSONObject().put("voiceName", id)), "Голос: $id")
                syncChoicesHighlight()
            }
            voicePills[id] = p
            voiceRow.addView(p)
        }
        col.addView(voiceRow)

        col.addView(makeSubLabel("Характер Алисы"))
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        val presets = listOf(
            Triple(
                "universal",
                "Универсальная",
                "Ты — голосовой ассистент Алиса на умной колонке Яндекс Станция Макс с открытой прошивкой OpenStation Max (полный root-доступ и живой доступ в интернет). Отвечай по-русски живо, дружелюбно, естественно и кратко (1-3 предложения, если не просят подробнее). Обязательно используй локальные инструменты: web_search, get_weather, fetch_url, set_volume, set_eq_preset, set_audio_route, set_led_ring, manage_timer, tv_hdmi_cec, remote_control, music_control и manage_apps."
            ),
            Triple(
                "concise",
                "Краткая и чёткая",
                "Ты — голосовой ассистент Алиса на Станции Макс. Отвечай максимально кратко и по делу на русском языке (1-2 предложения), сразу выполняй команды управления музыкой, громкостью, ТВ, подсветкой и поиском через доступные инструменты."
            ),
            Triple(
                "witty",
                "Дружелюбная с юмором",
                "Ты — весёлая и остроумная Алиса на Станции Макс. Общайся по-русски тепло, живо и иногда с лёгкой шуткой, оставаясь полезной и используя все встроенные инструменты колонки для музыки, погоды, ТВ и умного дома."
            )
        )
        presets.forEach { (key, label, fullPrompt) ->
            val p = makeChoicePill(label) {
                activePromptPreset = key
                pushConfigPatch(JSONObject().put("assistant", JSONObject().put("systemPrompt", fullPrompt)), "Характер: $label")
                syncChoicesHighlight()
            }
            promptPills[key] = p
            promptRow.addView(p)
        }
        col.addView(promptRow)

        col.addView(makeSubLabel("SNI-прокси обход и проверка микрофона"))
        val proxyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        listOf("ai-pool.comss.one" to "SNI: ai-pool.comss.one", "" to "Прямой (без прокси)").forEach { (host, label) ->
            val p = makeChoicePill(label) {
                curProxyHost = host
                pushConfigPatch(JSONObject().put("assistant", JSONObject().put("geminiProxyHost", host)), "Прокси обновлён")
                syncChoicesHighlight()
            }
            proxyPills[host] = p
            proxyRow.addView(p)
        }
        col.addView(proxyRow)

        tvStep2KwsLive = TextView(this).apply {
            text = "Офлайн Wake Word: ожидание — попробуйте сказать вслух «Алиса»!"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1838BDF8"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(9).toFloat()
            }
        }
        col.addView(tvStep2KwsLive)

        vStep2VuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#180F172A"))
                setStroke(dp(1), Color.parseColor("#3338BDF8"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

        val vuHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            }
        }
        val tvVuLabel = TextView(this).apply {
            text = "МИКРОФОН И VQE УРОВЕНЬ:"
            textSize = 10f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvStep2VuStats = TextView(this).apply {
            text = "0 RMS | Шум: 0 | vqe"
            textSize = 10f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.MONOSPACE
        }
        vuHeader.addView(tvVuLabel)
        vuHeader.addView(tvStep2VuStats)
        vStep2VuContainer.addView(vuHeader)

        val vuTrack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33000000"))
                cornerRadius = dp(4).toFloat()
            }
        }
        vStep2VuBarFill = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#10B981"),
                    Color.parseColor("#06B6D4"),
                    Color.parseColor("#3B82F6"),
                    Color.parseColor("#F59E0B")
                )
            ).apply {
                cornerRadius = dp(4).toFloat()
            }
        }
        vuTrack.addView(vStep2VuBarFill)
        vStep2VuContainer.addView(vuTrack)

        col.addView(vStep2VuContainer)
        return wrapStepScroll(col)
    }

    private fun buildStep3Pane(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(makePaneHeading(
            "Шаг 3 · Яндекс Музыка (вход на экране станции)",
            "Авторизация выполняется прямо с экрана станции по полному QR-коду с уменьшенным масштабом."
        ))

        tvStep3MusicStatus = TextView(this).apply {
            text = "Яндекс Музыка: проверка статуса..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A0C192E"))
                setStroke(dp(1), Color.parseColor("#55A855F7"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
        col.addView(tvStep3MusicStatus)

        val ymBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        ymBtns.addView(makeActionPill("🎵 Войти с экрана (полный QR)", goldHighlight = true) {
            try {
                startActivity(Intent(this, YandexMusicActivity::class.java).putExtra("tab", "auth"))
            } catch (t: Throwable) {
                Toast.makeText(this, "Ошибка запуска: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        ymBtns.addView(makeActionPill("🌊 Тест «Моя волна»") {
            StationAudioService.instance?.musicPlayer?.playMyWave("any", "", openUiOnAuthNeeded = true)
            Toast.makeText(this, "Запускаем Мою волну...", Toast.LENGTH_SHORT).show()
        })
        col.addView(ymBtns)

        col.addView(makeSubLabel("Выход звука"))
        val rRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        listOf(
            "auto" to "Авто (Джек)",
            "speakers" to "Динамики",
            "headphones" to "3.5мм AUX",
            "hdmi" to "HDMI (ТВ)",
            "both" to "Вместе"
        ).forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curRoute = id
                postAsync("http://127.0.0.1:8080/api/audio/route", JSONObject().put("route", id).toString())
                Toast.makeText(this, "Выход: $lbl", Toast.LENGTH_SHORT).show()
                syncChoicesHighlight()
            }
            routePills[id] = p
            rRow.addView(p)
        }
        col.addView(rRow)

        col.addView(makeSubLabel("Профиль эквалайзера"))
        val eqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "balanced" to "Баланс (Hi-Fi)",
            "bass_boost" to "Глубокий бас",
            "vocal" to "Вокал / Кино",
            "night" to "Ночной",
            "flat" to "Flat"
        ).forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curEq = id
                try {
                    startForegroundService(Intent(this, StationAudioService::class.java).apply {
                        action = StationAudioService.ACTION_SET_EQ
                        putExtra(StationAudioService.EXTRA_PRESET, id)
                    })
                } catch (_: Throwable) {}
                pushConfigPatch(JSONObject().put("audio", JSONObject().put("eqPreset", id)), "Эквалайзер: $lbl")
                syncChoicesHighlight()
            }
            eqPills[id] = p
            eqRow.addView(p)
        }
        col.addView(eqRow)
        return wrapStepScroll(col)
    }

    private fun buildStep4Pane(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(makePaneHeading(
            "Шаг 4 · Подсветка 24 LED, Кнопка и ТВ (HDMI-CEC)",
            "Настройте кольцо сверху корпуса и управление телевизором."
        ))

        col.addView(makeSubLabel("Эффект LED-кольца"))
        val ringRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        listOf(
            "music" to "Светомузыка",
            "rainbow" to "Радуга",
            "solid" to "Сплошной",
            "breathe" to "Дыхание",
            "spinner" to "Спиннер",
            "off" to "Выкл"
        ).forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curRingMode = id
                pushConfigPatch(JSONObject().put("ledRing", JSONObject().put("mode", id)), "Кольцо: $lbl")
                syncChoicesHighlight()
            }
            ringPills[id] = p
            ringRow.addView(p)
        }
        col.addView(ringRow)

        col.addView(makeSubLabel("Короткое нажатие верхней кнопки Action"))
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        listOf(
            "assistant" to "Алиса (Gemini)",
            "wyoming" to "Home Assistant",
            "play_pause" to "Пауза / Плей",
            "led_cycle" to "Смена подсветки"
        ).forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curActShort = id
                pushConfigPatch(JSONObject().put("input", JSONObject().put("actionShort", id)), "Кнопка: $lbl")
                syncChoicesHighlight()
            }
            actionPills[id] = p
            actRow.addView(p)
        }
        col.addView(actRow)

        val extraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        btnAutoBri = makeActionPill("☀ Автояркость: ВКЛ") {
            curAutoBri = !curAutoBri
            pushConfigPatch(JSONObject().put("ledRing", JSONObject().put("autoBrightness", curAutoBri)), "Автояркость обновлена")
            syncChoicesHighlight()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        val btnCecOn = makeActionPill("📺 Проверить ТВ (CEC)") {
            postAsync("http://127.0.0.1:8080/api/remote/cec", """{"cmd":"wakeup"}""")
            Toast.makeText(this, "HDMI-CEC Wake отправлен ТВ", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        val btnShowBoot = makeActionPill("✨ Стартовое шоу") {
            postAsync("http://127.0.0.1:8080/api/system/startup_show", "{}")
        }
        extraRow.addView(btnAutoBri)
        extraRow.addView(btnCecOn)
        extraRow.addView(btnShowBoot)
        col.addView(extraRow)

        val cecHint = TextView(this).apply {
            text = "Примечание: в Станции Макс нет ИК-диода, поэтому управление телевизором работает по HDMI-CEC (включите Anynet+/Bravia Sync/Simplink в настройках ТВ)."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(cecHint)
        return wrapStepScroll(col)
    }

    private fun buildStep5Pane(): ScrollView {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(makePaneHeading(
            "Шаг 5 · Прошивка настроена и готова!",
            "Теперь Станция Макс полностью автономна. Нажмите «Завершить» внизу для входа на экран «Сфера»."
        ))

        tvStep5Summary = TextView(this).apply {
            text = "Проверка конфигурации..."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2010B981"))
                setStroke(dp(1), Color.parseColor("#5510B981"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        col.addView(tvStep5Summary)

        col.addView(makeSubLabel("Памятка по управлению"))
        val cheats = listOf(
            "🎙 «Алиса, включи музыку»" to "Нативно запустит «Мою волну» Яндекса прямо в лаунчере.",
            "◀ / ▶ Экраны BORK × Duo" to "Плавный морфинг: «Сфера» ↔ «Музыка и ТВ» ↔ «Станция».",
            "✨ Системный оверлей" to "Вызов «Алисы» поверх YouTube или любого приложения выводит живую сферу внизу.",
            "☁ Автономный пульт и OTA" to "Пульт по http://<IP>:8080 или station-max.local; обновления приходят с GitHub."
        )
        for (i in cheats.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            for (j in 0..1) {
                val (h, b) = cheats[i + j]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#AA0E1626"))
                        setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (j == 0) marginEnd = dp(8)
                    }
                    addView(TextView(this@SetupWizardActivity).apply {
                        text = h
                        setTextColor(Color.parseColor("#E8C686"))
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    addView(TextView(this@SetupWizardActivity).apply {
                        text = b
                        setTextColor(Color.parseColor("#CBD5E1"))
                        textSize = 11f
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                row.addView(card)
            }
            col.addView(row)
        }
        return wrapStepScroll(col)
    }

    private fun updateStepUi(focusContent: Boolean) {
        tvStepCounter.text = "Шаг $currentStep из 5"
        btnPrev.visibility = if (currentStep > 1) View.VISIBLE else View.INVISIBLE
        btnNext.visibility = if (currentStep < 5) View.VISIBLE else View.GONE
        btnFinish.visibility = if (currentStep == 5) View.VISIBLE else View.GONE

        stepPills.forEachIndexed { idx, tv ->
            val active = (idx + 1 == currentStep)
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (active) {
                    setColor(Color.parseColor("#33E8C686"))
                    setStroke(dp(1), Color.parseColor("#FFE8C686"))
                } else {
                    setColor(Color.TRANSPARENT)
                }
            }
            tv.setTextColor(Color.parseColor(if (active) "#F8FAFC" else "#94A3B8"))
        }

        stepContents.forEachIndexed { idx, sv ->
            sv.visibility = if (idx + 1 == currentStep) View.VISIBLE else View.GONE
        }
        syncChoicesHighlight()
        if (focusContent) {
            handler.post {
                if (currentStep == 5) {
                    btnFinish.requestFocus()
                } else {
                    btnNext.requestFocus()
                }
            }
        }
    }

    private fun syncChoicesHighlight() {
        fun styleMap(map: Map<String, TextView>, selectedKey: String) {
            map.forEach { (k, tv) ->
                val sel = (k == selectedKey)
                tv.isSelected = sel
                tv.setTextColor(Color.parseColor(if (sel) "#FFE8C686" else "#E2E8F0"))
            }
        }
        styleMap(voicePills, curVoice)
        styleMap(proxyPills, curProxyHost)
        styleMap(promptPills, activePromptPreset)
        styleMap(routePills, curRoute)
        styleMap(eqPills, curEq)
        styleMap(ringPills, curRingMode)
        styleMap(actionPills, curActShort)
        btnAutoBri.text = if (curAutoBri) "☀ Автояркость: ВКЛ" else "☀ Автояркость: ВЫКЛ"
    }

    private fun openGeminiKeyDialog() {
        val et = EditText(this).apply {
            hint = "Вставьте ключ Gemini API (AIza... или AQ...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Ключ Google Gemini Live API")
            .setMessage("Получите бесплатный ключ на aistudio.google.com/apikey. Удобнее всего вставить его через QR с телефона.")
            .setView(et)
            .setPositiveButton("Сохранить и проверить") { _, _ ->
                val k = et.text?.toString()?.trim() ?: ""
                if (k.isNotEmpty()) {
                    curHasKey = true
                    pushConfigPatch(
                        JSONObject().put(
                            "assistant",
                            JSONObject().put("enabled", true).put("mode", "gemini").put("geminiApiKey", k)
                        ),
                        "Ключ сохранён, проверяем..."
                    )
                    handler.postDelayed({ testGeminiLiveConnection(overrideKey = k) }, 300L)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun testGeminiLiveConnection(overrideKey: String? = null) {
        tvStep2GeminiStatus.text = "⏳ Подключение к Gemini Live (прямо и через SNI)..."
        tvStep2GeminiStatus.setTextColor(Color.parseColor("#E8C686"))
        thread(isDaemon = true) {
            try {
                val body = JSONObject().put("geminiProxyHost", curProxyHost)
                if (!overrideKey.isNullOrBlank()) body.put("geminiApiKey", overrideKey)
                val conn = (URL("http://127.0.0.1:8080/api/assistant/test").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 2000
                    readTimeout = 13000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val j = JSONObject(txt)
                val ok = j.optBoolean("ok", false)
                val via = j.optString("via", "direct")
                val ms = j.optLong("latencyMs", 0L)
                val err = j.optString("error", "")
                val code = j.optInt("code", 0)
                handler.post {
                    if (ok) {
                        tvStep2GeminiStatus.text = "✓ Gemini Live работает! (маршрут: $via · ${ms} мс)"
                        tvStep2GeminiStatus.setTextColor(Color.parseColor("#6EE7B7"))
                        tvStep2GeminiStatus.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#2410B981"))
                            setStroke(dp(1), Color.parseColor("#6610B981"))
                            cornerRadius = dp(10).toFloat()
                        }
                    } else {
                        tvStep2GeminiStatus.text = "✗ Ошибка${if (code != 0) " ($code)" else ""}: $err"
                        tvStep2GeminiStatus.setTextColor(Color.parseColor("#FCA5A5"))
                        tvStep2GeminiStatus.background = GradientDrawable().apply {
                            setColor(Color.parseColor("#24EF4444"))
                            setStroke(dp(1), Color.parseColor("#66EF4444"))
                            cornerRadius = dp(10).toFloat()
                        }
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    tvStep2GeminiStatus.text = "✗ Ошибка теста: ${t.message}"
                    tvStep2GeminiStatus.setTextColor(Color.parseColor("#FCA5A5"))
                }
            }
        }
    }

    private fun applyPrivateDns(mode: String, host: String) {
        thread(isDaemon = true) {
            val payload = JSONObject().put("mode", mode).put("host", host).toString()
            httpPostJson("http://127.0.0.1:8080/api/system/dns", payload)
            handler.post {
                Toast.makeText(this, "Private DNS: $host", Toast.LENGTH_SHORT).show()
                pollDaemonStatus()
            }
        }
    }

    private fun completeSetupAndClose() {
        btnFinish.isEnabled = false
        thread(isDaemon = true) {
            val ok = httpPostJson("http://127.0.0.1:8080/api/setup/complete", "{}")
            handler.post {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_WIZARD_DISMISSED_VER, CURRENT_WIZARD_VER)
                    .apply()
                Toast.makeText(
                    this,
                    if (ok) "Настройка завершена! Добро пожаловать в OpenStation Max" else "Готово!",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun pollDaemonStatus() {
        val localIp = detectLocalIp()
        thread(isDaemon = true) {
            try {
                val conn = (URL("http://127.0.0.1:8080/api/status").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 900
                    readTimeout = 900
                }
                if (conn.responseCode == 200) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val root = JSONObject(txt)
                    val ip = root.optString("ip", "").ifBlank { localIp }
                    val host = root.optString("hostname", "station-max")
                    val fw = root.optString("firmwareVersion", "1.2.3")
                    val dnsMode = root.optString("privateDnsMode", "")
                    val dnsHost = root.optString("privateDnsHost", "")
                    val hw = root.optJSONObject("hardware")
                    val cfg = root.optJSONObject("config")
                    val setupDone = cfg?.optBoolean("setupCompleted", false) ?: false
                    val astCfg = cfg?.optJSONObject("assistant")
                    val auCfg = cfg?.optJSONObject("audio")
                    val ringCfg = cfg?.optJSONObject("ledRing")
                    val inCfg = cfg?.optJSONObject("input")
                    val ast = root.optJSONObject("assistant")
                    val ww = ast?.optJSONObject("wakeWord")
                    val music = root.optJSONObject("music")
                    val audio = root.optJSONObject("audio")
                    val engine = audio?.optJSONObject("engine")
                    val aRms = engine?.optDouble("monoRms", 0.0)?.toInt() ?: 0
                    val aNoise = engine?.optInt("noiseFloor", 0) ?: 0
                    val aBackend = engine?.optString("backend", "aaudio_1ch_16k_vqe") ?: "aaudio_1ch_16k_vqe"

                    val sysMusicSnap = StationAudioService.instance?.musicPlayer?.getSnapshot()
                    val mLoggedIn = sysMusicSnap?.account?.loggedIn ?: (music?.optBoolean("hasAuth", false) ?: false)
                    val mUser = sysMusicSnap?.account?.login?.ifBlank { music?.optString("userLogin", "") ?: "" }
                        ?: (music?.optString("userLogin", "") ?: "")
                    val mPlus = sysMusicSnap?.account?.hasPlus ?: (music?.optBoolean("hasPlus", false) ?: false)

                    handler.post {
                        if (initialSetupCompleted == null) {
                            initialSetupCompleted = setupDone
                        } else if (!manualLaunch && initialSetupCompleted == false && setupDone) {
                            Toast.makeText(this, "Настройка завершена с веб-пульта!", Toast.LENGTH_SHORT).show()
                            dismissWizardAndFinish(markSkipped = true)
                            return@post
                        }

                        curIp = ip
                        curHostname = host
                        curFwVersion = fw
                        curTempC = hw?.optDouble("socTempC", 0.0) ?: 0.0
                        curFb5 = hw?.optBoolean("fb5Present", false) ?: false
                        curDnsMode = dnsMode
                        curDnsHost = dnsHost
                        curHasKey = astCfg?.optBoolean("geminiApiKeySet", false) == true ||
                            !(astCfg?.optString("geminiApiKey", "") ?: "").isBlank()
                        curVoice = astCfg?.optString("voiceName", "Aoede") ?: "Aoede"
                        curProxyHost = astCfg?.optString("geminiProxyHost", "ai-pool.comss.one") ?: "ai-pool.comss.one"
                        curWakeEn = astCfg?.optBoolean("wakeWordEnabled", true) ?: true
                        curKwsReady = ww?.optBoolean("ready", false) ?: false
                        curKwsDetections = ww?.optInt("detections", 0) ?: 0
                        curRoute = auCfg?.optString("outputRoute", "auto") ?: "auto"
                        curEq = auCfg?.optString("eqPreset", "balanced") ?: "balanced"
                        curRingMode = ringCfg?.optString("mode", "music") ?: "music"
                        curAutoBri = ringCfg?.optBoolean("autoBrightness", true) ?: true
                        curActShort = inCfg?.optString("actionShort", "assistant") ?: "assistant"
                        curMusicLoggedIn = mLoggedIn
                        curMusicUser = mUser
                        curMusicPlus = mPlus
                        curAudioRms = aRms
                        curAudioNoise = aNoise
                        curAudioBackend = aBackend

                        renderPolledStatus(ast, ww)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun renderPolledStatus(ast: JSONObject?, ww: JSONObject?) {
        updateQrCode(curIp)

        tvOrbCaption.text = when (LiveOrbState.assistantState) {
            "listening" -> "Слушает..."
            "speaking" -> "Алиса говорит"
            "thinking" -> "Думает..."
            else -> if (curHasKey) "Алиса готова" else "Ждёт ключ API"
        }

        // Left Checklist
        tvChkDaemon.text = "✓ Система v$curFwVersion готова"
        tvChkDaemon.setTextColor(Color.parseColor("#6EE7B7"))

        if (curDnsMode == "hostname" && curDnsHost.isNotBlank()) {
            tvChkDns.text = "✓ Private DNS: $curDnsHost"
            tvChkDns.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvChkDns.text = "• DNS: ${curDnsMode.ifBlank { "авто" }}"
            tvChkDns.setTextColor(Color.parseColor("#E8C686"))
        }

        if (curHasKey) {
            tvChkGemini.text = "✓ Ключ Gemini Live API задан"
            tvChkGemini.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvChkGemini.text = "• Нужен ключ Gemini (Шаг 2)"
            tvChkGemini.setTextColor(Color.parseColor("#FDE68A"))
        }

        if (curKwsReady) {
            tvChkKws.text = "✓ Фраза «Алиса» (${curKwsDetections})"
            tvChkKws.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvChkKws.text = "• Wake Word «Алиса» выкл"
            tvChkKws.setTextColor(Color.parseColor("#94A3B8"))
        }

        if (curMusicLoggedIn) {
            tvChkMusic.text = "✓ Музыка: @${curMusicUser.ifBlank { "OK" }}"
            tvChkMusic.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvChkMusic.text = "• Яндекс Музыка (опц.)"
            tvChkMusic.setTextColor(Color.parseColor("#94A3B8"))
        }

        // Step 1 Cards
        tvDiagIp.text = "http://$curIp:8080\n$curHostname.local"
        tvDiagDns.text = if (curDnsMode == "hostname" && curDnsHost.isNotBlank()) {
            "$curDnsHost (активен)"
        } else {
            "Авто (${curDnsMode.ifBlank { "нет" }})"
        }
        tvDiagAudio.text = "Hi-Fi · Yandex VQE DSP\n${curAudioBackend.replace('_', ' ')} (${curAudioRms} RMS)"
        tvDiagHw.text = "Голос и экран\n" + if (curFb5) "Передний экран: активен" else "Передний экран: выкл"

        // Step 2 Gemini Banner (only overwrite default hint, preserve explicit test results until next click)
        if (!tvStep2GeminiStatus.text.startsWith("✓ Gemini") && !tvStep2GeminiStatus.text.startsWith("✗ Ошибка") && !tvStep2GeminiStatus.text.startsWith("⏳")) {
            if (curHasKey) {
                tvStep2GeminiStatus.text = "✓ Ключ Gemini задан · Нажмите «Проверить Gemini Live» для теста"
                tvStep2GeminiStatus.setTextColor(Color.parseColor("#6EE7B7"))
                tvStep2GeminiStatus.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2410B981"))
                    setStroke(dp(1), Color.parseColor("#5510B981"))
                    cornerRadius = dp(10).toFloat()
                }
            } else {
                tvStep2GeminiStatus.text = "Ключ не задан. Отсканируйте QR телефоном и вставьте ключ с aistudio.google.com/apikey"
                tvStep2GeminiStatus.setTextColor(Color.parseColor("#FDE68A"))
                tvStep2GeminiStatus.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#24F59E0B"))
                    setStroke(dp(1), Color.parseColor("#55F59E0B"))
                    cornerRadius = dp(10).toFloat()
                }
            }
        }

        val vadSpeech = ww?.optBoolean("vadSpeech", false) ?: false
        val newDetect = if (lastKwsDetections in 0 until curKwsDetections) true else false
        lastKwsDetections = curKwsDetections
        tvStep2KwsLive.text = when {
            newDetect || (ast?.optString("state", "idle") == "listening") ->
                "✓ Отлично! «Алиса» услышала вас (срабатываний: $curKwsDetections)"
            vadSpeech ->
                "🎙 Речь слышна (скажите чётко «Алиса»)"
            curKwsReady ->
                "Офлайн Wake Word активен — скажите вслух «Алиса» для проверки (срабатываний: $curKwsDetections)"
            else ->
                "Wake Word выключен (активация кнопкой Action или с экрана)"
        }

        if (::tvStep2VuStats.isInitialized) {
            tvStep2VuStats.text = "$curAudioRms RMS | Шум: $curAudioNoise | $curAudioBackend"
        }
        if (::vStep2VuBarFill.isInitialized && ::vStep2VuContainer.isInitialized) {
            val pct = Math.min(100, Math.max(0, (Math.log10(curAudioRms.coerceAtLeast(1).toDouble()) / 3.8 * 100).toInt()))
            val trackWidth = (vStep2VuContainer.width - dp(24)).coerceAtLeast(10)
            val targetW = (trackWidth * pct) / 100
            val lp = vStep2VuBarFill.layoutParams
            if (lp.width != targetW) {
                lp.width = targetW
                vStep2VuBarFill.layoutParams = lp
            }
        }

        // Step 3 Music
        if (curMusicLoggedIn) {
            val plusStr = if (curMusicPlus) " · Яндекс Плюс" else ""
            tvStep3MusicStatus.text = "✓ Вход выполнен: @${curMusicUser.ifBlank { "OK" }}$plusStr"
            tvStep3MusicStatus.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvStep3MusicStatus.text = "Вход не выполнен — нажмите «Войти с экрана (полный QR)» выше."
            tvStep3MusicStatus.setTextColor(Color.parseColor("#E8C686"))
        }
        refreshBleRemoteStatusText()

        // Step 5 Summary
        tvStep5Summary.text = buildString {
            append("• Автономный сервер: http://$curIp:8080 ($curHostname.local)\n")
            append("• Голосовой ассистент «Алиса»: ").append(if (curHasKey) "Ключ API задан · Голос $curVoice\n" else "Без ключа API (можно добавить позже в веб-пульте)\n")
            append("• Яндекс Музыка: ").append(if (curMusicLoggedIn) "@$curMusicUser · Моя волна готова\n" else "Не авторизована (можно войти позже)\n")
            append("• Звук и подсветка: Выход $curRoute · EQ $curEq · Кольцо $curRingMode")
        }

        syncChoicesHighlight()
    }

    private fun updateQrCode(ip: String) {
        val cleanIp = ip.ifBlank { "127.0.0.1" }
        val url = "http://$cleanIp:8080/#wizard"
        if (url != lastQrUrl) {
            lastQrUrl = url
            tvQrUrl.text = url
            QrGenerator.generateBitmap(url, 180)?.let { ivQr.setImageBitmap(it) }
        }
    }

    private fun detectLocalIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) return ip
                    }
                }
            }
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }

    private fun makePaneHeading(title: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(8))
        addView(TextView(this@SetupWizardActivity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        addView(TextView(this@SetupWizardActivity).apply {
            text = sub
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun makeSubLabel(textStr: String): TextView = TextView(this).apply {
        text = textStr.uppercase(Locale.getDefault())
        setTextColor(Color.parseColor("#E8C686"))
        textSize = 10.5f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(5))
    }

    private fun makeCheckRow(txt: String): TextView = TextView(this).apply {
        text = txt
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 12f
        setPadding(0, dp(3), 0, dp(3))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun makeDiagCard(label: String, valueTv: TextView, marginEndDp: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#B8111C33"), Color.parseColor("#C40B1323"))
        ).apply {
            setStroke(dp(1), Color.parseColor("#3A38BDF8"))
            cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (marginEndDp > 0) marginEnd = dp(marginEndDp)
        }
        addView(TextView(this@SetupWizardActivity).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
        })
        valueTv.setTextColor(Color.WHITE)
        valueTv.textSize = 12.5f
        valueTv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        valueTv.setPadding(0, dp(2), 0, 0)
        addView(valueTv)
    }

    private fun makeActionPill(label: String, goldHighlight: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor(if (goldHighlight) "#0B0F17" else "#F1F5F9"))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setBackgroundResource(if (goldHighlight) R.drawable.glass_bg_pill_gold else R.drawable.glass_bg_pill)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
        }
        setOnClickListener { onClick() }
    }

    private fun makeChoicePill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#E2E8F0"))
        textSize = 11.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(11), dp(6), dp(11), dp(6))
        setBackgroundResource(R.drawable.glass_bg_tile)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f).setDuration(90).start()
        }
        setOnClickListener { onClick() }
    }

    private fun startRemoteAutoPair() {
        val mgr = StationAudioService.instance?.bleRemoteManager
        if (mgr == null) {
            handler.postDelayed({ StationAudioService.instance?.bleRemoteManager?.let { startRemoteAutoPair() } }, 1000L)
            return
        }
        mgr.startAutoScanAndPair(20000L) { st, detail ->
            handler.post {
                if (::tvStep1RemoteStatus.isInitialized) {
                    val col = when (st) {
                        "connected" -> "#6EE7B7"
                        "error", "not_found" -> "#FDE68A"
                        else -> "#38BDF8"
                    }
                    tvStep1RemoteStatus.text = "🎮 $detail"
                    tvStep1RemoteStatus.setTextColor(Color.parseColor(col))
                }
            }
        }
    }

    private fun triggerRemoteBuzz() {
        val mgr = StationAudioService.instance?.bleRemoteManager
        if (mgr == null) {
            Toast.makeText(this, "BLE-служба ещё запускается...", Toast.LENGTH_SHORT).show()
            return
        }
        mgr.triggerBuzzer { ok, msg ->
            handler.post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (::tvStep1RemoteStatus.isInitialized) {
                    tvStep1RemoteStatus.text = (if (ok) "🔔 " else "🎮 ") + msg
                }
            }
        }
    }

    private fun refreshBleRemoteStatusText() {
        val mgr = StationAudioService.instance?.bleRemoteManager ?: return
        val st = mgr.getRemoteStatus()
        if (::tvStep1RemoteStatus.isInitialized && st.bonded) {
            val bat = if (st.batteryPct >= 0) " · Заряд ${st.batteryPct}%" else ""
            val stateStr = if (st.connected) "Подключён" else "Сопряжён (спящий режим)"
            tvStep1RemoteStatus.text = "✓ Пульт ${st.name}: $stateStr$bat"
            tvStep1RemoteStatus.setTextColor(Color.parseColor("#6EE7B7"))
        }
    }

    private fun pushConfigPatch(patch: JSONObject, toastMsg: String) {
        thread(isDaemon = true) {
            val ok = httpPostJson("http://127.0.0.1:8080/api/config", patch.toString())
            handler.post {
                if (ok) Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun postAsync(url: String, jsonBody: String) {
        thread(isDaemon = true) { httpPostJson(url, jsonBody) }
    }

    private fun httpPostJson(url: String, jsonBody: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 1500
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Throwable) {
            false
        }
    }
}
