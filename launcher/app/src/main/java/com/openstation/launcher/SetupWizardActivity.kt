package com.openstation.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SetupWizardActivity : Activity() {

    companion object {
        const val PREFS_NAME = "openstation_launcher_prefs"
        const val KEY_WIZARD_DISMISSED_VER = "wizard_dismissed_ver"
        const val CURRENT_WIZARD_VER = 2
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_POST_OTA_VER = "post_ota_version"
        private const val TOTAL_STEPS = 7
    }

    private val handler = Handler(Looper.getMainLooper())
    private val miscRepairHelper by lazy { MiscRepairDialogHelper(this, handler) }
    private var currentStep = 1
    private var prevStep = 1
    private var manualLaunch = false
    private var postOtaVersion = ""
    private var initialSetupCompleted: Boolean? = null
    private var lastQrUrl: String? = null
    private var lastQrBitmap: Bitmap? = null
    private var hasQrState = false
    private var lastKwsDetections = -1
    private var progressAnim: ValueAnimator? = null

    // Stage chrome & navigation
    private lateinit var rootFrame: FrameLayout
    private lateinit var shellContainer: LinearLayout
    private lateinit var auroraView: WizardAuroraCanvasView
    private lateinit var headerOtaBadge: TextView
    private lateinit var stepRail: WizardStepRailView
    private lateinit var stepStage: FrameLayout
    private lateinit var screenViews: Array<LinearLayout>
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnFinish: LinearLayout
    private lateinit var tvStepCounter: TextView
    private lateinit var footerProgressFill: View

    // Screen 1: Remote control
    private lateinit var remoteHeroView: WizardHeroGlyphView
    private lateinit var tvRemoteStatus: TextView
    private lateinit var tvRemoteAutoPill: TextView
    private lateinit var ivQrScreen1: ImageView
    private lateinit var tvQrUrlScreen1: TextView

    // Screen 2: Wi-Fi & Phone Link
    private lateinit var wifiHeroView: WizardHeroGlyphView
    private lateinit var tvWifiStatus: TextView
    private lateinit var btnWifiDnsToggle: TextView
    private lateinit var ivQrScreen2: ImageView
    private lateinit var tvQrUrlScreen2: TextView

    // Screen 3: OTA Update
    private lateinit var otaHeroView: WizardHeroGlyphView
    private lateinit var tvOtaTitle: TextView
    private lateinit var tvOtaVersionPill: TextView
    private lateinit var tvOtaStatus: TextView
    private lateinit var btnOtaCheck: TextView
    private lateinit var btnOtaInstall: TextView

    // Screen 4: Assistant & ADB Agent
    private lateinit var wizardOrbScreen4: AliceOrbView
    private lateinit var waveView: WizardWaveformView
    private lateinit var tvAliceStatus: TextView
    private lateinit var tvAliceHeading: TextView
    private lateinit var tvStep4WakePhraseLabel: TextView
    private lateinit var llStep4WakePhraseRow: LinearLayout
    private lateinit var tvPersonalityLabel: TextView
    private lateinit var btnStep4WakeToggle: TextView
    private lateinit var btnStep4AgentToggle: TextView
    private val namePills = LinkedHashMap<String, TextView>()
    private val wakePills = LinkedHashMap<String, TextView>()
    private val voicePills = LinkedHashMap<String, TextView>()
    private val promptPills = LinkedHashMap<String, TextView>()

    // Screen 5: Yandex Music & Audio
    private lateinit var musicHeroView: WizardHeroGlyphView
    private lateinit var tvMusicStatus: TextView
    private val routePills = LinkedHashMap<String, TextView>()
    private val eqPills = LinkedHashMap<String, TextView>()
    private val hwEqPills = LinkedHashMap<String, TextView>()
    private lateinit var llHwEqSection: LinearLayout

    // Screen 6: 24-LED Halo & TV
    private lateinit var ledRingPreview: WizardLedRingPreviewView
    private lateinit var tvLedCaption: TextView
    private val ringPills = LinkedHashMap<String, TextView>()
    private val colorSwatches = LinkedHashMap<String, View>()
    private val actionPills = LinkedHashMap<String, TextView>()
    private lateinit var btnAutoBri: TextView

    // Screen 7: Finish & Summary
    private lateinit var wizardOrbFinish: AliceOrbView
    private lateinit var tvFinBadgeRemote: TextView
    private lateinit var tvFinBadgeNet: TextView
    private lateinit var tvFinBadgeAlice: TextView
    private lateinit var tvFinBadgeMedia: TextView

    // Cached state from daemon
    private var curIp = ""
    private var curWifiSsid = ""
    private var curFwVersion = "1.6.3"
    private var curAgentMode = true
    private var curDnsMode = ""
    private var curDnsHost = ""
    private var curOtaState = "idle"
    private var curOtaBusy = false
    private var curOtaLatest = ""
    private var curOtaUpdateAvailable = false
    private var curOtaPct = 0
    private var curOtaProgressText = ""
    private var curOtaChangelog = ""
    private var curOtaError = ""
    private var curHasKey = false
    private var curAssistantName = "Алиса"
    private var curWakePhrase = "alisa"
    private var curWakeEngine = "kws"
    private var curStockFallback = false
    private var curAvailableWakePhrases: Set<String> = emptySet()
    private var curVoice = "Aoede"
    private var curProxyHost = "ai-pool.comss.one"
    private var curWakeEn = true
    private var curKwsReady = false
    private var curKwsDetections = 0
    private var curVadSpeech = false
    private var curAudioRms = 0
    private var curAudioNoise = 0
    private var curMusicLoggedIn = false
    private var curMusicUser = ""
    private var curMusicPlus = false
    private var curRoute = "auto"
    private var curEq = "balanced"
    private var curHwEqSupported = false
    private var curHwEqProfile = "original"
    private var curRingMode = "music"
    private var curRingColorHex = "#00d4ff"
    private var curAutoBri = true
    private var curActShort = "assistant"
    private var activePromptPreset = ""
    private var bleAutoStarted = false
    private var remoteAutoSearching = false
    private var initialWindowFocusHandled = false
    private var remoteSearchDetail = "Ищем пульт по Bluetooth LE..."
    private val spriteAnimators = ArrayList<ValueAnimator>()
    private val remoteRetryRunnable = Runnable {
        if (!isFinishing && !isDestroyed && currentStep == 1) {
            startRemoteAutoPair()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDaemonStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)
        LiquidGlassKit.ensureInitialized(this)
        manualLaunch = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        postOtaVersion = intent?.getStringExtra(EXTRA_POST_OTA_VER)?.trim().orEmpty()
        StationAudioService.ensureStarted(this)

        buildUi()
        runEntranceAnimation()
        updateStepUi(focusContent = true, animateTransition = false)
        updateQrCodes(detectLocalIp())
        pollDaemonStatus()
        if (!bleAutoStarted) {
            bleAutoStarted = true
            handler.postDelayed({
                if (!isFinishing && !isDestroyed && currentStep == 1) {
                    startRemoteAutoPair()
                }
            }, 450L)
        }
        btnNext.post {
            if (!isFinishing && !isDestroyed && currentStep < TOTAL_STEPS) {
                btnNext.requestFocus()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !initialWindowFocusHandled) {
            initialWindowFocusHandled = true
            if (currentStep == TOTAL_STEPS) btnFinish.requestFocus() else btnNext.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        StationAudioService.ensureStarted(this)
        auroraView.startAnimations()
        remoteHeroView.startAnimations()
        wifiHeroView.startAnimations()
        otaHeroView.startAnimations()
        musicHeroView.startAnimations()
        waveView.startAnimations()
        ledRingPreview.startAnimations()
        spriteAnimators.forEach { if (!it.isRunning) it.start() }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        if (currentStep == 1) {
            val st = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
            if (st?.connected != true && !remoteAutoSearching) {
                handler.postDelayed(remoteRetryRunnable, 350L)
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(remoteRetryRunnable)
        auroraView.stopAnimations()
        remoteHeroView.stopAnimations()
        wifiHeroView.stopAnimations()
        otaHeroView.stopAnimations()
        musicHeroView.stopAnimations()
        waveView.stopAnimations()
        ledRingPreview.stopAnimations()
        spriteAnimators.forEach { it.cancel() }
        miscRepairHelper.dismissIfShowing()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            if (currentStep > 1) {
                prevStep = currentStep
                currentStep--
                updateStepUi(focusContent = true, animateTransition = true)
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
            postAsync("http://127.0.0.1:8080/api/setup/dismiss_ota_wizard", "{}")
        }
        finishWithFade()
    }

    private fun finishWithFade() {
        shellContainer.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(220L)
            .withEndAction { finish() }
            .start()
    }

    private fun runEntranceAnimation() {
        stepRail.alpha = 0f
        stepRail.translationY = -dp(14).toFloat()
        stepStage.alpha = 0f
        stepStage.scaleX = 0.97f
        stepStage.scaleY = 0.97f

        stepRail.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(380L)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
        stepStage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(60L)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator(2.1f))
            .start()
    }

    private fun buildUi() {
        rootFrame = FrameLayout(this)

        val ivBg = ImageView(this).apply {
            setImageResource(R.drawable.wall_alpine_twilight)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.14f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        auroraView = WizardAuroraCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootFrame.addView(ivBg)
        rootFrame.addView(auroraView)

        shellContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(28), dp(14), dp(28), dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootFrame.addView(shellContainer)

        // ===== CLEAN TOP BAR =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(0, 0, 0, dp(10))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        brandRow.addView(TextView(this).apply {
            text = "OpenStation Max"
            setTextColor(Color.parseColor("#F5DFB0"))
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        headerOtaBadge = TextView(this).apply {
            text = if (postOtaVersion.isNotEmpty()) "🚀 OTA v$postOtaVersion" else "v$curFwVersion"
            setTextColor(Color.parseColor(if (postOtaVersion.isNotEmpty()) "#0B0F17" else "#6EE7B7"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (postOtaVersion.isNotEmpty()) {
                    setColor(Color.parseColor("#E8C686"))
                } else {
                    setColor(Color.parseColor("#2410B981"))
                    setStroke(dp(1), Color.parseColor("#5510B981"))
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        brandRow.addView(headerOtaBadge)

        val railWrap = FrameLayout(this).apply {
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
        }
        stepRail = WizardStepRailView(
            this,
            arrayOf("1. Пульт", "2. Wi-Fi", "3. OTA", "4. Алиса", "5. Музыка", "6. Свет", "7. Финал")
        ) { targetStep ->
            if (targetStep != currentStep) {
                prevStep = currentStep
                currentStep = targetStep
                updateStepUi(focusContent = true, animateTransition = true)
            }
        }
        railWrap.addView(
            stepRail,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        val btnSkip = makeActionPill("Пропустить") {
            dismissWizardAndFinish(markSkipped = true)
        }
        header.addView(brandRow)
        header.addView(railWrap)
        header.addView(btnSkip)
        shellContainer.addView(header)

        // ===== DEDICATED SCREEN STAGE (No persistent sidebar, full-width slide) =====
        stepStage = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        screenViews = arrayOf(
            buildScreen1Remote(),
            buildScreen2Wifi(),
            buildScreen3Ota(),
            buildScreen4Alice(),
            buildScreen5Music(),
            buildScreen6Lights(),
            buildScreen7Finish()
        )
        screenViews.forEachIndexed { idx, view ->
            view.visibility = if (idx == 0) View.VISIBLE else View.GONE
            stepStage.addView(view)
        }
        shellContainer.addView(stepStage)

        // ===== CLEAN BOTTOM NAVIGATION BAR =====
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(0, dp(10), 0, 0)
        }

        btnPrev = makeActionPill("← Назад") {
            if (currentStep > 1) {
                prevStep = currentStep
                currentStep--
                updateStepUi(focusContent = true, animateTransition = true)
            }
        }

        val footerMidCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvStepCounter = TextView(this).apply {
            text = "Экран 1 из $TOTAL_STEPS"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            gravity = Gravity.CENTER
        }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2AFFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(4)).apply {
                topMargin = dp(4)
            }
        }
        footerProgressFill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#E8C686"))
            ).apply {
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(dp(220) / TOTAL_STEPS, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        track.addView(footerProgressFill)
        footerMidCol.addView(tvStepCounter)
        footerMidCol.addView(track)

        btnNext = TextView(this).apply {
            text = "Далее →"
            setTextColor(Color.parseColor("#0B0F17"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(9), dp(26), dp(9))
            setBackgroundResource(R.drawable.bg_pill_gold)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f).setDuration(120L).start()
            }
            setOnClickListener {
                if (currentStep < TOTAL_STEPS) {
                    prevStep = currentStep
                    currentStep++
                    updateStepUi(focusContent = true, animateTransition = true)
                }
            }
        }

        btnFinish = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(10))
            setBackgroundResource(R.drawable.bg_pill_gold)
            visibility = View.GONE
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f).setDuration(120L).start()
            }
            setOnClickListener { completeSetupAndClose() }
            addView(TextView(this@SetupWizardActivity).apply {
                text = "✨ Начать работу"
                setTextColor(Color.parseColor("#0B0F17"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
        }

        footer.addView(btnPrev)
        footer.addView(footerMidCol)
        footer.addView(btnNext)
        footer.addView(btnFinish)
        shellContainer.addView(footer)

        setContentView(rootFrame)
    }

    private fun makeGlassScreenContainer(horizontal: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(34), dp(24), dp(34), dp(24))
        LiquidGlassKit.applyGlass(
            this,
            radiusDp = 28f,
            baseTint = Color.parseColor("#AE080F1D"),
            normalStroke = Color.parseColor("#34FFFFFF")
        )
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // ===== SCREEN 1: REMOTE CONTROL =====
    private fun buildScreen1Remote(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = true)

        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(28)
            }
        }
        remoteHeroView = WizardHeroGlyphView(this, WizardHeroGlyphView.Mode.REMOTE).apply {
            layoutParams = LinearLayout.LayoutParams(dp(188), dp(188)).apply { bottomMargin = dp(10) }
        }
        tvRemoteStatus = makeStatusBadge("📡 Автопоиск пульта BLE...")
        heroCol.addView(remoteHeroView)
        heroCol.addView(tvRemoteStatus)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rightCol.addView(makeScreenTitle("Шаг 1 · Подключение пульта"))
        rightCol.addView(makeScreenSub("Поиск уже идёт сам — нажимать ничего не нужно! Удерживайте «Домой» и «Громкость −» ~5 сек, пока индикатор не начнёт мигать."))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(16)
            }
        }
        tvRemoteAutoPill = TextView(this).apply {
            text = "📡 Идёт автопоиск BLE-пульта..."
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#240EA5E9"))
                setStroke(dp(1), Color.parseColor("#6638BDF8"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        btnRow.addView(tvRemoteAutoPill)
        btnRow.addView(makeActionPill("🔔 Зуммер (позвонить)") {
            triggerRemoteBuzz()
        })
        rightCol.addView(btnRow)

        val qrTuple = makeCompactQrBox("Телефон работает как пульт и клавиатура")
        ivQrScreen1 = qrTuple.first
        tvQrUrlScreen1 = qrTuple.second
        rightCol.addView(qrTuple.third)

        root.addView(heroCol)
        root.addView(rightCol)
        return root
    }

    // ===== SCREEN 2: WI-FI & NETWORK =====
    private fun buildScreen2Wifi(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = true)

        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(28)
            }
        }
        wifiHeroView = WizardHeroGlyphView(this, WizardHeroGlyphView.Mode.WIFI).apply {
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).apply { bottomMargin = dp(12) }
        }
        tvWifiStatus = makeStatusBadge("Проверка Wi-Fi сети...")
        heroCol.addView(wifiHeroView)
        heroCol.addView(tvWifiStatus)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rightCol.addView(makeScreenTitle("Шаг 2 · Wi-Fi и Интернет"))
        rightCol.addView(makeScreenSub("Интернет нужен для Алисы, Яндекс Музыки и автоматических обновлений."))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(16)
            }
        }
        btnRow.addView(makeActionPill("📶 Выбрать сеть Wi-Fi", goldHighlight = true) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                Toast.makeText(this, "Откройте настройки Wi-Fi вручную", Toast.LENGTH_SHORT).show()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) })

        btnWifiDnsToggle = makeActionPill("🛡 DNS: Авто") {
            val useComss = curDnsMode != "hostname" || curDnsHost != "dns.comss.one"
            applyPrivateDns(if (useComss) "hostname" else "opportunistic", if (useComss) "dns.comss.one" else "")
        }
        btnRow.addView(btnWifiDnsToggle)
        rightCol.addView(btnRow)

        val qrTuple = makeCompactQrBox("Мастер в телефоне — удобно вставить API-ключ")
        ivQrScreen2 = qrTuple.first
        tvQrUrlScreen2 = qrTuple.second
        rightCol.addView(qrTuple.third)

        root.addView(heroCol)
        root.addView(rightCol)
        return root
    }

    // ===== SCREEN 3: OTA UPDATE =====
    private fun buildScreen3Ota(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = true)

        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(28)
            }
        }
        otaHeroView = WizardHeroGlyphView(this, WizardHeroGlyphView.Mode.OTA).apply {
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).apply { bottomMargin = dp(12) }
        }
        tvOtaVersionPill = makeStatusBadge("OpenStation Max v$curFwVersion")
        heroCol.addView(otaHeroView)
        heroCol.addView(tvOtaVersionPill)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvOtaTitle = makeScreenTitle("Шаг 3 · Обновление прошивки (OTA)")
        rightCol.addView(tvOtaTitle)
        rightCol.addView(makeScreenSub("Станция проверяет и устанавливает обновления напрямую с GitHub без ПК."))

        tvOtaStatus = TextView(this).apply {
            text = "Текущая версия: v$curFwVersion"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12.5f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#240F1C33"))
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(14)
            }
        }
        rightCol.addView(tvOtaStatus)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        btnOtaCheck = makeActionPill("🔄 Проверить обновления", goldHighlight = true) {
            postAsync("http://127.0.0.1:8080/api/ota/check", "{}")
            Toast.makeText(this, "Проверяем обновления на GitHub...", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ pollDaemonStatus() }, 450L)
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) }

        btnOtaInstall = makeActionPill("⬇ Установить обновление", goldHighlight = true) {
            postAsync("http://127.0.0.1:8080/api/ota/install", """{"force":false}""")
            Toast.makeText(this, "Запускаем установку OTA...", Toast.LENGTH_SHORT).show()
        }.apply { visibility = View.GONE }

        btnRow.addView(btnOtaCheck)
        btnRow.addView(btnOtaInstall)
        rightCol.addView(btnRow)

        root.addView(heroCol)
        root.addView(rightCol)
        return root
    }

    // ===== SCREEN 4: ALICE & ADB AGENT =====
    private fun buildScreen4Alice(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = true)

        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(24)
            }
        }
        val orbWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(124), dp(124)).apply { bottomMargin = dp(10) }
        }
        orbWrap.addView(makeSpriteView(R.drawable.sprite_alice_orb, 118, spinSec = 26L, isFrameChild = true).apply {
            alpha = 0.88f
        })
        wizardOrbScreen4 = AliceOrbView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        orbWrap.addView(wizardOrbScreen4)
        val waveBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#32081224"))
                setStroke(dp(1), Color.parseColor("#3038BDF8"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(265), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        waveView = WizardWaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        }
        waveBox.addView(waveView)

        tvAliceStatus = makeStatusBadge("Проверка Алисы...")
        heroCol.addView(orbWrap)
        heroCol.addView(waveBox)
        heroCol.addView(tvAliceStatus)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvAliceHeading = makeScreenTitle("Шаг 4 · Ассистент «Алиса»")
        rightCol.addView(tvAliceHeading)

        rightCol.addView(makeSubLabel("Имя и слово активации · «Алиса»/«Яндекс» — для штатного споттера, остальные — OpenStation KWS"))
        val nameRow = makeChoiceRow()
        listOf("Алиса", "Джарвис", "Пятница", "Макс").forEach { n ->
            val p = makeChoicePill(n) { applyAssistantNameChoice(n) }
            namePills[n] = p
            nameRow.addView(p)
        }
        val customNamePill = makeChoicePill("✏ Своё") { openAssistantNameDialog() }
        namePills["custom"] = customNamePill
        nameRow.addView(customNamePill)
        rightCol.addView(nameRow)

        tvStep4WakePhraseLabel = makeSubLabel("Слово активации (штатный споттер, для имени «Алиса»)")
        rightCol.addView(tvStep4WakePhraseLabel)
        llStep4WakePhraseRow = makeChoiceRow()
        listOf("alisa" to "Алиса", "yandex" to "Яндекс").forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                val phraseRu = if (id == "yandex") "яндекс" else "алиса"
                if (!curAvailableWakePhrases.contains(phraseRu)) return@makeChoicePill
                curWakePhrase = id
                pushConfigPatch(JSONObject().put("assistant", JSONObject().put("wakePhrase", id)), "Слово активации: $lbl")
                syncChoicesHighlight()
            }
            wakePills[id] = p
            llStep4WakePhraseRow.addView(p)
        }
        rightCol.addView(llStep4WakePhraseRow)

        rightCol.addView(makeSubLabel("Голос (Gemini Live)"))
        val voiceRow = makeChoiceRow()
        listOf("Aoede" to "Aoede (жен.)", "Kore" to "Kore (яркий)", "Puck" to "Puck (муж.)", "Charon" to "Charon (глуб.)").forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curVoice = id
                LiveOrbState.updateAssistantPersona(curAssistantName, id)
                pushConfigPatch(JSONObject().put("assistant", JSONObject().put("voiceName", id)), "Голос: $id")
                syncChoicesHighlight()
            }
            voicePills[id] = p
            voiceRow.addView(p)
        }
        rightCol.addView(voiceRow)

        tvPersonalityLabel = makeSubLabel("Характер общения")
        rightCol.addView(tvPersonalityLabel)
        val promptRow = makeChoiceRow()
        AssistantPersonalities.presets.forEach { (key, label, fullPrompt) ->
            val p = makeChoicePill(label) {
                activePromptPreset = key
                pushConfigPatch(JSONObject().put("assistant", JSONObject().put("systemPrompt", fullPrompt)), "Характер: $label")
                syncChoicesHighlight()
            }
            promptPills[key] = p
            promptRow.addView(p)
        }
        rightCol.addView(promptRow)

        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        actRow.addView(makeActionPill("🔑 Ключ", goldHighlight = true) {
            openGeminiKeyDialog()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        actRow.addView(makeActionPill("⚡ Проверить") {
            testGeminiLiveConnection()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })

        btnStep4WakeToggle = makeActionPill("🎙 Отклик: ВКЛ") {
            curWakeEn = !curWakeEn
            pushConfigPatch(
                JSONObject().put("assistant", JSONObject().put("wakeWordEnabled", curWakeEn)),
                if (curWakeEn) "Отклик включён" else "Отклик выключен"
            )
            syncChoicesHighlight()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }

        btnStep4AgentToggle = makeActionPill("🤖 Агент ADB: ВКЛ") {
            toggleAgentMode()
        }

        actRow.addView(btnStep4WakeToggle)
        actRow.addView(btnStep4AgentToggle)
        rightCol.addView(actRow)

        root.addView(heroCol)
        root.addView(rightCol)
        return root
    }

    // ===== SCREEN 5: YANDEX MUSIC & AUDIO =====
    private fun buildScreen5Music(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = true)

        val leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                marginEnd = dp(24)
            }
        }
        musicHeroView = WizardHeroGlyphView(this, WizardHeroGlyphView.Mode.MUSIC).apply {
            layoutParams = LinearLayout.LayoutParams(dp(124), dp(124)).apply {
                bottomMargin = dp(8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        leftCard.addView(musicHeroView)
        leftCard.addView(makeScreenTitle("Шаг 5 · Яндекс Музыка"))
        leftCard.addView(makeScreenSub("Музыка с «Моей волной» и Hi-Fi потоком."))

        tvMusicStatus = TextView(this).apply {
            text = "Проверка аккаунта..."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12.5f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A0C192E"))
                setStroke(dp(1), Color.parseColor("#55A855F7"))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(14)
            }
        }
        leftCard.addView(tvMusicStatus)

        val ymBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        ymBtns.addView(makeActionPill("📺 Войти (QR на ТВ)", goldHighlight = true) {
            try {
                startActivity(Intent(this, YandexMusicActivity::class.java).putExtra("tab", "auth"))
            } catch (t: Throwable) {
                Toast.makeText(this, "Ошибка запуска: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        ymBtns.addView(makeActionPill("🌊 Моя волна") {
            StationAudioService.instance?.musicPlayer?.playMyWave("any", "", openUiOnAuthNeeded = true)
            Toast.makeText(this, "Запускаем Мою волну...", Toast.LENGTH_SHORT).show()
        })
        leftCard.addView(ymBtns)

        val rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
        }
        rightCard.addView(makeScreenTitle("Звучание и Выход"))

        rightCard.addView(makeSubLabel("Куда выводить звук"))
        val rRow = makeChoiceRow()
        listOf("auto" to "Авто", "speakers" to "Динамики", "headphones" to "3.5 мм", "hdmi" to "HDMI (ТВ)").forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curRoute = id
                postAsync("http://127.0.0.1:8080/api/audio/route", JSONObject().put("route", id).toString())
                Toast.makeText(this, "Выход: $lbl", Toast.LENGTH_SHORT).show()
                syncChoicesHighlight()
            }
            routePills[id] = p
            rRow.addView(p)
        }
        rightCard.addView(rRow)

        rightCard.addView(makeSubLabel("Профиль эквалайзера"))
        val eqRow = makeChoiceRow()
        listOf("balanced" to "Баланс", "bass_boost" to "Глубокий бас", "vocal" to "Кино / Голос", "night" to "Ночной", "rock" to "Rock").forEach { (id, lbl) ->
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
        rightCard.addView(eqRow)

        llHwEqSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        llHwEqSection.addView(makeSubLabel("Усилитель SY6045S (Аппаратный DSP)"))
        val hwRow = makeChoiceRow()
        listOf("original" to "Оригинал", "hifi" to "Hi-Fi", "night" to "Ночь", "bass" to "Бас", "club" to "Клуб").forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curHwEqProfile = id
                LiveOrbState.updateHardwareEq(id, curHwEqSupported)
                postAsync("http://127.0.0.1:8080/api/audio/hardware_eq", JSONObject().put("profile", id).toString())
                Toast.makeText(this, "DSP: $lbl", Toast.LENGTH_SHORT).show()
                syncChoicesHighlight()
            }
            hwEqPills[id] = p
            hwRow.addView(p)
        }
        llHwEqSection.addView(hwRow)
        rightCard.addView(llHwEqSection)

        root.addView(leftCard)
        root.addView(rightCard)
        return root
    }

    // ===== SCREEN 6: 24-LED HALO & TV =====
    private fun buildScreen6Lights(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = true)

        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(dp(310), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(24)
            }
        }
        val ringWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).apply { bottomMargin = dp(8) }
        }
        ringWrap.addView(makeSpriteView(R.drawable.sprite_bork_dial_ring, 168, isFrameChild = true).apply {
            alpha = 0.85f
        })
        ledRingPreview = WizardLedRingPreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(146), dp(146), Gravity.CENTER)
        }
        ringWrap.addView(ledRingPreview)

        val capRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        capRow.addView(makeSpriteView(R.drawable.sprite_sys_eq, 22, floatDp = 2f).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
        })
        tvLedCaption = TextView(this).apply {
            text = "24 RGB LED · Светомузыка"
            setTextColor(Color.parseColor("#E8C686"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        capRow.addView(tvLedCaption)
        capRow.addView(makeSpriteView(R.drawable.sprite_sys_display, 22, floatDp = 2f).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        })
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
        }
        listOf(
            "#00d4ff" to "Голубой",
            "#e8c686" to "Золото",
            "#8b5cf6" to "Неон",
            "#10b981" to "Изумруд",
            "#ec4899" to "Розовый",
            "#ffffff" to "Белый"
        ).forEachIndexed { idx, (hex, title) ->
            val sw = View(this).apply {
                val sz = dp(24)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    if (idx < 5) marginEnd = dp(8)
                }
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(110L).start()
                }
                setOnClickListener {
                    curRingColorHex = hex
                    pushConfigPatch(JSONObject().put("ledRing", JSONObject().put("color", hex)), "Цвет: $title")
                    syncChoicesHighlight()
                }
            }
            colorSwatches[hex] = sw
            swatchRow.addView(sw)
        }
        heroCol.addView(ringWrap)
        heroCol.addView(capRow)
        heroCol.addView(swatchRow)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rightCol.addView(makeScreenTitle("Шаг 6 · Подсветка и кнопка"))

        rightCol.addView(makeSubLabel("Эффект LED-кольца"))
        val ringRow = makeChoiceRow()
        listOf("music" to "Светомузыка", "rainbow" to "Радуга", "solid" to "Сплошной", "breathe" to "Дыхание", "spinner" to "Спиннер", "off" to "Выкл").forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curRingMode = id
                pushConfigPatch(JSONObject().put("ledRing", JSONObject().put("mode", id)), "Кольцо: $lbl")
                syncChoicesHighlight()
            }
            ringPills[id] = p
            ringRow.addView(p)
        }
        rightCol.addView(ringRow)

        rightCol.addView(makeSubLabel("Нажатие верхней кнопки корпуса"))
        val actRow = makeChoiceRow()
        listOf("assistant" to "Алиса", "wyoming" to "Home Assistant", "play_pause" to "Пауза", "led_cycle" to "Подсветка").forEach { (id, lbl) ->
            val p = makeChoicePill(lbl) {
                curActShort = id
                pushConfigPatch(JSONObject().put("input", JSONObject().put("actionShort", id)), "Кнопка: $lbl")
                syncChoicesHighlight()
            }
            actionPills[id] = p
            actRow.addView(p)
        }
        rightCol.addView(actRow)

        val extraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        btnAutoBri = makeActionPill("☀ Автояркость: ВКЛ") {
            curAutoBri = !curAutoBri
            pushConfigPatch(JSONObject().put("ledRing", JSONObject().put("autoBrightness", curAutoBri)), "Автояркость обновлена")
            syncChoicesHighlight()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        val btnCec = makeActionPill("📺 Включить ТВ (CEC)") {
            postAsync("http://127.0.0.1:8080/api/remote/cec", """{"cmd":"wakeup"}""")
            Toast.makeText(this, "HDMI-CEC Wake отправлен ТВ", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) }
        val btnShow = makeActionPill("✨ Стартовое шоу") {
            postAsync("http://127.0.0.1:8080/api/system/startup_show", "{}")
        }
        extraRow.addView(btnAutoBri)
        extraRow.addView(btnCec)
        extraRow.addView(btnShow)
        rightCol.addView(extraRow)

        root.addView(heroCol)
        root.addView(rightCol)
        return root
    }

    // ===== SCREEN 7: CELEBRATION FINISH =====
    private fun buildScreen7Finish(): LinearLayout {
        val root = makeGlassScreenContainer(horizontal = false).apply {
            gravity = Gravity.CENTER
        }

        val heroTrio = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(96)).apply {
                bottomMargin = dp(8)
            }
        }
        heroTrio.addView(makeSpriteView(R.drawable.sprite_station_hero, 92, floatDp = 3.5f).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16)
        })
        val orbWrap7 = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(16) }
        }
        orbWrap7.addView(makeSpriteView(R.drawable.sprite_alice_orb, 84, spinSec = 24L, isFrameChild = true).apply {
            alpha = 0.88f
        })
        wizardOrbFinish = AliceOrbView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        orbWrap7.addView(wizardOrbFinish)
        heroTrio.addView(orbWrap7)
        heroTrio.addView(makeSpriteView(R.drawable.sprite_ota_cloud, 76, floatDp = 3f))
        root.addView(heroTrio)

        root.addView(TextView(this).apply {
            text = "Станция настроена и готова!"
            setTextColor(Color.parseColor("#F5DFB0"))
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Нажмите «Начать работу» ниже, чтобы перейти на главный экран ТВ."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        })

        val tileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        tvFinBadgeRemote = makeSummaryTile()
        tvFinBadgeNet = makeSummaryTile()
        tvFinBadgeAlice = makeSummaryTile()
        tvFinBadgeMedia = makeSummaryTile()
        listOf(tvFinBadgeRemote, tvFinBadgeNet, tvFinBadgeAlice, tvFinBadgeMedia).forEachIndexed { idx, tile ->
            tile.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx < 3) marginEnd = dp(10)
            }
            tileRow.addView(tile)
        }
        root.addView(tileRow)

        val tipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listOf(
            "🎙 «Алиса, включи мою волну»",
            "🔄 «Алиса, перезагрузи демон»",
            "🤖 Агент ADB активен по стоку"
        ).forEachIndexed { idx, txt ->
            tipsRow.addView(TextView(this).apply {
                text = txt
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1AFFFFFF"))
                    setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                    cornerRadius = dp(999).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < 2) marginEnd = dp(10)
                }
            })
        }
        root.addView(tipsRow)

        return root
    }

    private fun updateStepUi(focusContent: Boolean, animateTransition: Boolean = true) {
        auroraView.setStep(currentStep, TOTAL_STEPS)
        stepRail.setActiveStep(currentStep)

        tvStepCounter.text = "Экран $currentStep из $TOTAL_STEPS"
        btnPrev.visibility = if (currentStep > 1) View.VISIBLE else View.INVISIBLE
        btnNext.visibility = if (currentStep < TOTAL_STEPS) View.VISIBLE else View.GONE
        btnFinish.visibility = if (currentStep == TOTAL_STEPS) View.VISIBLE else View.GONE

        val targetWidth = (dp(220) * currentStep) / TOTAL_STEPS
        progressAnim?.cancel()
        val startW = footerProgressFill.layoutParams.width
        if (animateTransition && startW != targetWidth) {
            progressAnim = ValueAnimator.ofInt(startW, targetWidth).apply {
                duration = 280L
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { va ->
                    footerProgressFill.layoutParams = footerProgressFill.layoutParams.apply {
                        width = va.animatedValue as Int
                    }
                    footerProgressFill.requestLayout()
                }
                start()
            }
        } else {
            footerProgressFill.layoutParams = footerProgressFill.layoutParams.apply { width = targetWidth }
            footerProgressFill.requestLayout()
        }

        val forward = currentStep >= prevStep
        val slideOffset = dp(48).toFloat() * (if (forward) 1f else -1f)

        screenViews.forEachIndexed { idx, view ->
            view.animate().cancel()
            val isTarget = (idx + 1 == currentStep)
            if (isTarget) {
                view.visibility = View.VISIBLE
                if (animateTransition) {
                    view.alpha = 0f
                    view.translationX = slideOffset
                    view.scaleX = 0.975f
                    view.scaleY = 0.975f
                    view.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(260L)
                        .setInterpolator(DecelerateInterpolator(2.1f))
                        .start()
                } else {
                    view.alpha = 1f
                    view.translationX = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            } else {
                if (animateTransition && view.visibility == View.VISIBLE) {
                    view.animate()
                        .alpha(0f)
                        .translationX(-slideOffset * 0.6f)
                        .setDuration(170L)
                        .withEndAction {
                            if (idx + 1 != currentStep) {
                                view.visibility = View.GONE
                                view.translationX = 0f
                                view.alpha = 1f
                            }
                        }
                        .start()
                } else {
                    view.visibility = View.GONE
                    view.translationX = 0f
                    view.alpha = 1f
                }
            }
        }

        syncChoicesHighlight()
        if (currentStep == 1) {
            val st = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
            if (st?.connected != true && !remoteAutoSearching) {
                handler.removeCallbacks(remoteRetryRunnable)
                handler.postDelayed(remoteRetryRunnable, 350L)
            }
        } else {
            handler.removeCallbacks(remoteRetryRunnable)
            remoteAutoSearching = false
        }
        if (focusContent) {
            handler.post {
                if (currentStep == TOTAL_STEPS) btnFinish.requestFocus() else btnNext.requestFocus()
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
        val presetName = listOf("Алиса", "Джарвис", "Пятница", "Макс")
            .firstOrNull { it.equals(curAssistantName, ignoreCase = true) }
        namePills["custom"]?.text = if (presetName == null) "✏ $curAssistantName" else "✏ Своё"
        styleMap(namePills, presetName ?: "custom")
        val isAlisa = curAssistantName.equals("Алиса", ignoreCase = true) ||
            curAssistantName.equals("Alisa", ignoreCase = true)
        val showWakeRow = isAlisa && curAvailableWakePhrases.isNotEmpty()
        tvStep4WakePhraseLabel.visibility = if (showWakeRow) View.VISIBLE else View.GONE
        llStep4WakePhraseRow.visibility = if (showWakeRow) View.VISIBLE else View.GONE
        wakePills.forEach { (k, tv) ->
            val phraseRu = if (k == "yandex") "яндекс" else "алиса"
            val available = curAvailableWakePhrases.contains(phraseRu)
            val sel = (k == curWakePhrase) && available
            tv.isEnabled = available
            tv.alpha = if (available) 1f else 0.45f
            tv.isSelected = sel
            tv.setTextColor(Color.parseColor(if (sel) "#FFE8C686" else "#E2E8F0"))
        }
        styleMap(voicePills, curVoice)
        styleMap(promptPills, activePromptPreset)
        styleMap(routePills, curRoute)
        styleMap(eqPills, curEq)
        llHwEqSection.visibility = if (curHwEqSupported) View.VISIBLE else View.GONE
        styleMap(hwEqPills, curHwEqProfile)
        styleMap(ringPills, curRingMode)
        styleMap(actionPills, curActShort)
        btnAutoBri.text = if (curAutoBri) "☀ Автояркость: ВКЛ" else "☀ Автояркость: ВЫКЛ"

        btnStep4WakeToggle.text = if (curWakeEn) "🎙 Отклик: ВКЛ" else "🎙 Отклик: ВЫКЛ"
        btnStep4WakeToggle.setTextColor(Color.parseColor(if (curWakeEn) "#6EE7B7" else "#94A3B8"))

        btnStep4AgentToggle.text = if (curAgentMode) "🤖 Агент ADB: ВКЛ" else "🤖 Агент ADB: ВЫКЛ"
        btnStep4AgentToggle.setBackgroundResource(if (curAgentMode) R.drawable.bg_pill_gold else R.drawable.bg_pill)
        btnStep4AgentToggle.setTextColor(Color.parseColor(if (curAgentMode) "#0B0F17" else "#94A3B8"))

        val parsedRingCol = try {
            Color.parseColor(curRingColorHex.ifBlank { "#00d4ff" })
        } catch (_: Throwable) {
            Color.parseColor("#00d4ff")
        }
        ledRingPreview.ringMode = curRingMode
        ledRingPreview.ringColor = parsedRingCol
        val ringTitleRu = when (curRingMode) {
            "music" -> "Светомузыка"
            "rainbow" -> "Радуга"
            "solid" -> "Сплошной цвет"
            "breathe" -> "Дыхание"
            "spinner" -> "Спиннер"
            "off" -> "Выкл"
            else -> curRingMode
        }
        tvLedCaption.text = "24 RGB LED · $ringTitleRu ($curRingColorHex)"

        colorSwatches.forEach { (hex, sw) ->
            val selected = hex.equals(curRingColorHex, ignoreCase = true)
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hex))
                setStroke(
                    dp(if (selected) 3 else 1),
                    if (selected) Color.WHITE else Color.parseColor("#44FFFFFF")
                )
            }
        }
    }

    private fun toggleAgentMode() {
        val next = !curAgentMode
        curAgentMode = next
        syncChoicesHighlight()
        postAsync("http://127.0.0.1:8080/api/assistant/agent_mode", JSONObject().put("enabled", next).toString())
        Toast.makeText(
            this,
            if (next) "Агент ADB активен" else "Агент ADB выключен",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyAssistantNameChoice(newName: String) {
        val clean = newName.trim().ifEmpty { "Алиса" }
        curAssistantName = clean
        val patch = JSONObject().put("name", clean)
        val wantMale = AssistantPersonalities.isLikelyMaleName(clean)
        val curIsMale = AssistantPersonalities.isMaleVoice(curVoice)
        if (wantMale != curIsMale) {
            curVoice = AssistantPersonalities.defaultVoiceForName(clean)
            patch.put("voiceName", curVoice)
        }
        LiveOrbState.updateAssistantPersona(curAssistantName, curVoice)
        pushConfigPatch(JSONObject().put("assistant", patch), "Имя: «$clean»")
        syncChoicesHighlight()
        renderDynamicAssistantTexts()
    }

    private fun openAssistantNameDialog() {
        val et = EditText(this).apply {
            hint = "Например: Алиса, Джарвис, Ника..."
            setText(curAssistantName)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Своё имя ассистента")
            .setMessage("Ассистент и офлайн-активация перестроятся автоматически.")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = et.text?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) applyAssistantNameChoice(v)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renderDynamicAssistantTexts() {
        tvAliceHeading.text = "Шаг 4 · Ассистент «$curAssistantName»"
    }

    private fun openGeminiKeyDialog() {
        val et = EditText(this).apply {
            hint = "Вставьте ключ Gemini API (AIza... или AQ...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Ключ Google Gemini Live API")
            .setMessage("Удобно вставить через QR-код с экрана Wi-Fi.")
            .setView(et)
            .setPositiveButton("Сохранить и проверить") { _, _ ->
                val k = et.text?.toString()?.trim() ?: ""
                if (k.isNotEmpty()) {
                    curHasKey = true
                    pushConfigPatch(
                        JSONObject().put("assistant", JSONObject().put("enabled", true).put("mode", "gemini").put("geminiApiKey", k)),
                        "Ключ сохранён, проверяем..."
                    )
                    handler.postDelayed({ testGeminiLiveConnection(overrideKey = k) }, 300L)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun testGeminiLiveConnection(overrideKey: String? = null) {
        tvAliceStatus.text = "⏳ Проверка связи Gemini Live..."
        tvAliceStatus.setTextColor(Color.parseColor("#E8C686"))
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
                val ms = j.optLong("latencyMs", 0L)
                val err = j.optString("error", "")
                handler.post {
                    if (ok) {
                        tvAliceStatus.text = "✓ Связь работает (${ms} мс)"
                        tvAliceStatus.setTextColor(Color.parseColor("#6EE7B7"))
                    } else {
                        tvAliceStatus.text = "✗ Ошибка: ${err.ifBlank { "нет ответа" }}"
                        tvAliceStatus.setTextColor(Color.parseColor("#FCA5A5"))
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    tvAliceStatus.text = "✗ Ошибка теста: ${t.message}"
                    tvAliceStatus.setTextColor(Color.parseColor("#FCA5A5"))
                }
            }
        }
    }

    private fun applyPrivateDns(mode: String, host: String) {
        thread(isDaemon = true) {
            val payload = JSONObject().put("mode", mode).put("host", host).toString()
            httpPostJson("http://127.0.0.1:8080/api/system/dns", payload)
            handler.post {
                Toast.makeText(this, if (host.isNotBlank()) "Private DNS: $host" else "Private DNS: Авто", Toast.LENGTH_SHORT).show()
                pollDaemonStatus()
            }
        }
    }

    private fun completeSetupAndClose() {
        btnFinish.isEnabled = false
        auroraView.triggerCelebration()
        thread(isDaemon = true) {
            val ok = httpPostJson("http://127.0.0.1:8080/api/setup/complete", "{}")
            handler.post {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_WIZARD_DISMISSED_VER, CURRENT_WIZARD_VER)
                    .apply()
                Toast.makeText(
                    this,
                    if (ok) "Настройка завершена!" else "Готово!",
                    Toast.LENGTH_SHORT
                ).show()
                handler.postDelayed({ finishWithFade() }, 360L)
            }
        }
    }

    private fun detectWifiSsid(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val raw = wm?.connectionInfo?.ssid?.trim()?.trim('"').orEmpty()
            if (raw.isBlank() || raw.equals("<unknown ssid>", ignoreCase = true)) "" else raw
        } catch (_: Throwable) {
            ""
        }
    }

    private fun pollDaemonStatus() {
        val localIp = detectLocalIp()
        curWifiSsid = detectWifiSsid()
        if (!SetupAddress.isUsableIpv4(localIp)) {
            curIp = ""
            updateQrCodes("")
        }
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
                    val fw = root.optString("firmwareVersion", "1.6.3")
                    val miscRepairObj = root.optJSONObject("miscRepair")
                    val postOtaPending = root.optBoolean("postOtaWizardPending", false)
                    val postOtaVer = root.optString("postOtaWizardVersion", "").trim()
                    val dnsMode = root.optString("privateDnsMode", "")
                    val dnsHost = root.optString("privateDnsHost", "")
                    val ota = root.optJSONObject("ota")
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
                    val hwEq = root.optJSONObject("hardwareEq")
                    val engine = audio?.optJSONObject("engine")

                    val sysMusicSnap = StationAudioService.instance?.musicPlayer?.getSnapshot()
                    val mLoggedIn = sysMusicSnap?.account?.loggedIn ?: (music?.optBoolean("hasAuth", false) ?: false)
                    val mUser = sysMusicSnap?.account?.login?.ifBlank { music?.optString("userLogin", "") ?: "" }
                        ?: (music?.optString("userLogin", "") ?: "")
                    val mPlus = sysMusicSnap?.account?.hasPlus ?: (music?.optBoolean("hasPlus", false) ?: false)

                    handler.post {
                        if (initialSetupCompleted == null) {
                            initialSetupCompleted = setupDone
                        } else if (!manualLaunch && initialSetupCompleted == false && setupDone && !postOtaPending) {
                            Toast.makeText(this, "Настройка завершена с веб-пульта!", Toast.LENGTH_SHORT).show()
                            dismissWizardAndFinish(markSkipped = true)
                            return@post
                        }

                        if (postOtaVer.isNotEmpty() && postOtaVersion.isEmpty()) {
                            postOtaVersion = postOtaVer
                        }
                        curIp = localIp
                        curFwVersion = fw
                        curDnsMode = dnsMode
                        curDnsHost = dnsHost
                        curOtaState = ota?.optString("state", "idle") ?: "idle"
                        curOtaBusy = ota?.optBoolean("busy", false) ?: false
                        curOtaLatest = ota?.optString("latestVersion", "") ?: ""
                        curOtaUpdateAvailable = ota?.optBoolean("updateAvailable", false) ?: false
                        curOtaPct = ota?.optInt("progressPct", 0) ?: 0
                        curOtaProgressText = ota?.optString("progressText", "") ?: ""
                        curOtaChangelog = ota?.optString("changelog", "") ?: ""
                        curOtaError = ota?.optString("error", "") ?: ""

                        curHasKey = astCfg?.optBoolean("geminiApiKeySet", false) == true ||
                            !(astCfg?.optString("geminiApiKey", "") ?: "").isBlank()
                        curAssistantName = (astCfg?.optString("name", "") ?: "").trim().ifEmpty { "Алиса" }
                        curWakePhrase = astCfg?.optString("wakePhrase", "alisa") ?: "alisa"
                        curWakeEngine = ww?.optString("engine", "kws") ?: "kws"
                        curStockFallback = ww?.optBoolean("stockFallback", false) == true
                        val availArr = ww?.optJSONArray("availablePhrases")
                        val availSet = LinkedHashSet<String>()
                        if (availArr != null) {
                            for (i in 0 until availArr.length()) {
                                val ph = availArr.optString(i, "").trim().lowercase()
                                if (ph.isNotEmpty()) availSet.add(ph)
                            }
                        }
                        curAvailableWakePhrases = availSet
                        curVoice = astCfg?.optString("voiceName", "Aoede") ?: "Aoede"
                        curAgentMode = astCfg?.optBoolean("agentMode", ast?.optBoolean("agentMode", true) ?: true) ?: true
                        curProxyHost = astCfg?.optString("geminiProxyHost", "ai-pool.comss.one") ?: "ai-pool.comss.one"
                        curWakeEn = astCfg?.optBoolean("wakeWordEnabled", true) ?: true
                        curKwsReady = ww?.optBoolean("ready", false) ?: false
                        curKwsDetections = ww?.optInt("detections", 0) ?: 0
                        curVadSpeech = ww?.optBoolean("vadSpeech", false) ?: false
                        curAudioRms = engine?.optDouble("monoRms", 0.0)?.toInt() ?: 0
                        curAudioNoise = engine?.optInt("noiseFloor", 0) ?: 0

                        curRoute = auCfg?.optString("outputRoute", "auto") ?: "auto"
                        curEq = auCfg?.optString("eqPreset", "balanced") ?: "balanced"
                        curHwEqSupported = hwEq?.optBoolean("supported", false) ?: false
                        curHwEqProfile = hwEq?.optString("profile", "original") ?: "original"
                        curRingMode = ringCfg?.optString("mode", "music") ?: "music"
                        curRingColorHex = ringCfg?.optString("color", "#00d4ff") ?: "#00d4ff"
                        curAutoBri = ringCfg?.optBoolean("autoBrightness", true) ?: true
                        curActShort = inCfg?.optString("actionShort", "assistant") ?: "assistant"
                        curMusicLoggedIn = mLoggedIn
                        curMusicUser = mUser
                        curMusicPlus = mPlus

                        LiveOrbState.updateAssistantPersona(curAssistantName, curVoice)
                        LiveOrbState.updateHardwareEq(curHwEqProfile, curHwEqSupported)
                        activePromptPreset = AssistantPersonalities.keyForPrompt(astCfg?.optString("systemPrompt", "") ?: "")

                        renderPolledStatus(postOtaPending)
                        miscRepairHelper.handleDaemonState(miscRepairObj)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun renderPolledStatus(postOtaPending: Boolean) {
        updateQrCodes(curIp)
        renderDynamicAssistantTexts()

        val otaBannerVer = postOtaVersion.ifEmpty { if (postOtaPending) curFwVersion else "" }
        if (otaBannerVer.isNotEmpty()) {
            headerOtaBadge.text = "🚀 OTA v$otaBannerVer"
            headerOtaBadge.setTextColor(Color.parseColor("#0B0F17"))
            headerOtaBadge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8C686"))
                cornerRadius = dp(999).toFloat()
            }
        } else {
            headerOtaBadge.text = "v$curFwVersion"
            headerOtaBadge.setTextColor(Color.parseColor("#6EE7B7"))
            headerOtaBadge.background = GradientDrawable().apply {
                setColor(Color.parseColor("#2410B981"))
                setStroke(dp(1), Color.parseColor("#5510B981"))
                cornerRadius = dp(999).toFloat()
            }
        }

        // Screen 1 Remote
        val remoteSt = StationAudioService.instance?.bleRemoteManager?.getRemoteStatus()
        remoteHeroView.connected = remoteSt?.connected == true
        refreshBleRemoteStatusText()

        // Screen 2 Wi-Fi
        val netOk = SetupAddress.isUsableIpv4(curIp)
        wifiHeroView.connected = netOk
        tvWifiStatus.text = when {
            netOk && curWifiSsid.isNotBlank() -> "✓ $curWifiSsid · $curIp"
            netOk -> "✓ В сети · $curIp"
            else -> "Нет подключения к Wi-Fi"
        }
        tvWifiStatus.setTextColor(Color.parseColor(if (netOk) "#6EE7B7" else "#FDE68A"))
        val comssOn = curDnsMode == "hostname" && curDnsHost.equals("dns.comss.one", ignoreCase = true)
        btnWifiDnsToggle.text = if (comssOn) "🛡 DNS: comss.one" else "🛡 DNS: Авто"
        btnWifiDnsToggle.setTextColor(Color.parseColor(if (comssOn) "#6EE7B7" else "#E2E8F0"))

        // Screen 3 OTA
        otaHeroView.otaProgress = if (curOtaBusy) curOtaPct else 0
        otaHeroView.connected = !curOtaUpdateAvailable && !curOtaBusy
        otaHeroView.highlightGold = curOtaUpdateAvailable || otaBannerVer.isNotEmpty()
        tvOtaVersionPill.text = "OpenStation Max v$curFwVersion"
        tvOtaTitle.text = if (otaBannerVer.isNotEmpty()) {
            "Шаг 3 · Обновлено до v$otaBannerVer!"
        } else {
            "Шаг 3 · Обновление прошивки (OTA)"
        }
        tvOtaStatus.text = when {
            curOtaBusy -> "⏳ ${curOtaProgressText.ifBlank { "Загрузка обновления..." }} ($curOtaPct%)"
            curOtaUpdateAvailable && curOtaLatest.isNotBlank() ->
                "⬇ Доступна новая версия v$curOtaLatest\n${curOtaChangelog.ifBlank { "Улучшения стабильности и новые функции" }}"
            curOtaError.isNotBlank() -> "⚠ $curOtaError"
            otaBannerVer.isNotEmpty() ->
                "🚀 Установлена новая прошивка v$otaBannerVer!\nАгент ADB активен по стоку · Алиса перезагружает демон по голосу · Новый мастер настройки."
            curOtaState == "up_to_date" -> "✓ Установлена последняя актуальная версия (v$curFwVersion)"
            else -> "Текущая версия: v$curFwVersion · Нажмите «Проверить обновления»."
        }
        btnOtaInstall.visibility = if (curOtaUpdateAvailable && !curOtaBusy) View.VISIBLE else View.GONE
        btnOtaCheck.isEnabled = !curOtaBusy
        btnOtaCheck.alpha = if (curOtaBusy) 0.5f else 1f

        // Screen 4 Alice
        if (!tvAliceStatus.text.startsWith("⏳") && !tvAliceStatus.text.startsWith("✗")) {
            val activeWakeLabel = if (
                (curAssistantName.equals("Алиса", true) || curAssistantName.equals("Alisa", true)) &&
                curWakeEngine == "stock" &&
                curWakePhrase == "yandex"
            ) "Яндекс" else curAssistantName
            tvAliceStatus.text = when {
                !curHasKey -> "• Нужен ключ Gemini Live"
                curKwsReady -> "✓ Готова · отклик «$activeWakeLabel»" +
                    if (curStockFallback) " (резерв KWS)" else ""
                else -> "✓ Ключ задан · кнопка Action"
            }
            tvAliceStatus.setTextColor(Color.parseColor(if (curHasKey) "#6EE7B7" else "#FDE68A"))
        }
        waveView.updateTelemetry(curAudioRms, curAudioNoise, curVadSpeech)
        lastKwsDetections = curKwsDetections

        // Screen 5 Music
        if (curMusicLoggedIn) {
            val plusStr = if (curMusicPlus) " · Плюс" else ""
            tvMusicStatus.text = "✓ Вход выполнен: @${curMusicUser.ifBlank { "OK" }}$plusStr"
            tvMusicStatus.setTextColor(Color.parseColor("#6EE7B7"))
        } else {
            tvMusicStatus.text = "Аккаунт не подключён — нажмите «Войти (QR на ТВ)»"
            tvMusicStatus.setTextColor(Color.parseColor("#FDE68A"))
        }

        // Screen 7 Summary Tiles
        updateSummaryTile(
            tvFinBadgeRemote,
            ok = remoteSt?.connected == true,
            title = "🎮 Пульт",
            sub = if (remoteSt?.connected == true) (remoteSt.name.ifBlank { "Yandex BLE" }) else "Через ТВ / Телефон"
        )
        updateSummaryTile(
            tvFinBadgeNet,
            ok = netOk,
            title = "📶 Wi-Fi и OTA",
            sub = (if (netOk) curIp else "Без сети") + " · v$curFwVersion"
        )
        updateSummaryTile(
            tvFinBadgeAlice,
            ok = curHasKey,
            title = "🎙 «$curAssistantName»",
            sub = (if (curHasKey) curVoice else "Нужен ключ") + " · " + (if (curAgentMode) "Агент ВКЛ" else "Агент выкл")
        )
        updateSummaryTile(
            tvFinBadgeMedia,
            ok = curMusicLoggedIn || curRingMode != "off",
            title = "🎵 Музыка и Свет",
            sub = (if (curMusicLoggedIn) "@$curMusicUser" else curRoute) + " · $curRingMode"
        )

        syncChoicesHighlight()
    }

    private fun updateQrCodes(ip: String) {
        val url = SetupAddress.wizardUrl(ip)
        if (!hasQrState || url != lastQrUrl) {
            hasQrState = true
            lastQrUrl = url
            lastQrBitmap = if (url != null) QrGenerator.generateBitmap(url, 150) else null
        }
        val bmp = lastQrBitmap
        val label = url ?: "Подключите Wi-Fi для QR-доступа"
        listOf(ivQrScreen1 to tvQrUrlScreen1, ivQrScreen2 to tvQrUrlScreen2).forEach { (iv, tv) ->
            if (bmp != null) {
                iv.visibility = View.VISIBLE
                iv.setImageBitmap(bmp)
            } else {
                iv.visibility = View.INVISIBLE
                iv.setImageDrawable(null)
            }
            tv.text = label
        }
    }

    private fun detectLocalIp(): String = LocalNetworkAddress.find(this)

    private fun makeScreenTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F8FAFC"))
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun makeScreenSub(sub: String): TextView = TextView(this).apply {
        text = sub
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 13f
        setPadding(0, dp(4), 0, 0)
    }

    private fun makeSubLabel(textStr: String): TextView = TextView(this).apply {
        text = textStr.uppercase()
        setTextColor(Color.parseColor("#94A3B8"))
        textSize = 10f
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(9), 0, dp(5))
    }

    private fun makeChoiceRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        }
    }

    private fun makeStatusBadge(txt: String): TextView = TextView(this).apply {
        text = txt
        setTextColor(Color.parseColor("#E2E8F0"))
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#24FFFFFF"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            cornerRadius = dp(999).toFloat()
        }
    }

    private fun makeCompactQrBox(caption: String): Triple<ImageView, TextView, LinearLayout> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val whiteFrame = FrameLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) }
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        whiteFrame.addView(iv)
        val txtCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        txtCol.addView(TextView(this).apply {
            text = caption
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        val tvUrl = TextView(this).apply {
            text = "Загрузка адреса..."
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        }
        txtCol.addView(tvUrl)
        box.addView(whiteFrame)
        box.addView(txtCol)
        return Triple(iv, tvUrl, box)
    }

    private fun makeSummaryTile(): TextView = TextView(this).apply {
        setTextColor(Color.parseColor("#E2E8F0"))
        textSize = 11.5f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1FFFFFFF"))
            setStroke(dp(1), Color.parseColor("#2AFFFFFF"))
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun updateSummaryTile(tv: TextView, ok: Boolean, title: String, sub: String) {
        tv.text = "${if (ok) "✓" else "•"} $title\n$sub"
        tv.setTextColor(Color.parseColor(if (ok) "#D1FAE5" else "#FDE68A"))
        tv.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (ok) "#2010B981" else "#20F59E0B"))
            setStroke(dp(1), Color.parseColor(if (ok) "#5510B981" else "#44F59E0B"))
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun makeActionPill(label: String, goldHighlight: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor(if (goldHighlight) "#0B0F17" else "#F8FAFC"))
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setBackgroundResource(if (goldHighlight) R.drawable.bg_pill_gold else R.drawable.bg_pill)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f).setDuration(120L).start()
        }
        setOnClickListener { onClick() }
    }

    private fun makeChoicePill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#E2E8F0"))
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(13), dp(6), dp(13), dp(6))
        setBackgroundResource(R.drawable.bg_pill)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(7)
        }
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.07f else 1f).scaleY(if (hasFocus) 1.07f else 1f).setDuration(110L).start()
        }
        setOnClickListener { onClick() }
    }

    private fun makeSpriteView(
        resId: Int,
        sizeDp: Int,
        spinSec: Long = 0L,
        floatDp: Float = 0f,
        isFrameChild: Boolean = false
    ): ImageView {
        val iv = ImageView(this).apply {
            val sample = if (sizeDp <= 32) 8 else 4
            setImageBitmap(android.graphics.BitmapFactory.decodeResource(resources, resId,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = if (isFrameChild) {
                FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)
            } else {
                LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
        }
        if (spinSec > 0L) {
            val anim = ObjectAnimator.ofFloat(iv, View.ROTATION, 0f, 360f).apply {
                duration = spinSec * 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            spriteAnimators.add(anim)
            anim.start()
        }
        if (floatDp > 0f) {
            val amp = dp(1) * floatDp
            val anim = ObjectAnimator.ofFloat(iv, View.TRANSLATION_Y, -amp, amp).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }
            spriteAnimators.add(anim)
            anim.start()
        }
        return iv
    }

    private fun startRemoteAutoPair() {
        val mgr = StationAudioService.instance?.bleRemoteManager
        if (mgr == null) {
            handler.removeCallbacks(remoteRetryRunnable)
            handler.postDelayed(remoteRetryRunnable, 800L)
            return
        }
        val cur = mgr.getRemoteStatus()
        if (cur.connected) {
            remoteAutoSearching = false
            handler.removeCallbacks(remoteRetryRunnable)
            refreshBleRemoteStatusText()
            return
        }
        remoteAutoSearching = true
        remoteSearchDetail = "Ищем пульт по Bluetooth LE..."
        tvRemoteStatus.text = "📡 $remoteSearchDetail"
        tvRemoteStatus.setTextColor(Color.parseColor("#38BDF8"))
        tvRemoteAutoPill.text = "📡 Автопоиск BLE-пульта активен..."
        tvRemoteAutoPill.setTextColor(Color.parseColor("#7DD3FC"))

        mgr.startAutoScanAndPair(30000L) { st, detail ->
            handler.post {
                val col = when (st) {
                    "connected" -> "#6EE7B7"
                    "error", "not_found" -> "#FDE68A"
                    else -> "#38BDF8"
                }
                remoteSearchDetail = detail
                tvRemoteStatus.text = "🎮 $detail"
                tvRemoteStatus.setTextColor(Color.parseColor(col))
                if (st == "connected") {
                    remoteAutoSearching = false
                    handler.removeCallbacks(remoteRetryRunnable)
                    tvRemoteAutoPill.text = "✓ Пульт подключён"
                    tvRemoteAutoPill.setTextColor(Color.parseColor("#6EE7B7"))
                } else if (st == "not_found" || st == "error") {
                    remoteAutoSearching = false
                    if (!isFinishing && !isDestroyed && currentStep == 1) {
                        tvRemoteAutoPill.text = "📡 Повторяю поиск BLE-пульта..."
                        tvRemoteAutoPill.setTextColor(Color.parseColor("#FDE68A"))
                        handler.removeCallbacks(remoteRetryRunnable)
                        handler.postDelayed(remoteRetryRunnable, 1800L)
                    }
                } else {
                    tvRemoteAutoPill.text = "📡 Идёт сопряжение пульта..."
                    tvRemoteAutoPill.setTextColor(Color.parseColor("#38BDF8"))
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
                tvRemoteStatus.text = (if (ok) "🔔 " else "🎮 ") + msg
            }
        }
    }

    private fun refreshBleRemoteStatusText() {
        val mgr = StationAudioService.instance?.bleRemoteManager ?: return
        val st = mgr.getRemoteStatus()
        val bat = if (st.batteryPct >= 0) " · ${st.batteryPct}%" else ""
        if (st.connected) {
            remoteAutoSearching = false
            handler.removeCallbacks(remoteRetryRunnable)
            tvRemoteStatus.text = "✓ Пульт ${st.name} подключён$bat"
            tvRemoteStatus.setTextColor(Color.parseColor("#6EE7B7"))
            tvRemoteAutoPill.text = "✓ Пульт подключён$bat"
            tvRemoteAutoPill.setTextColor(Color.parseColor("#6EE7B7"))
            return
        }
        if (remoteAutoSearching && currentStep == 1) {
            tvRemoteStatus.text = "📡 $remoteSearchDetail"
            tvRemoteStatus.setTextColor(Color.parseColor("#38BDF8"))
            return
        }
        tvRemoteStatus.text = when {
            st.bonded -> "Пульт ${st.name} в памяти$bat"
            else -> "Ожидание пульта («Домой» + «−»)"
        }
        tvRemoteStatus.setTextColor(Color.parseColor("#E8C686"))
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
                connectTimeout = 2000
                readTimeout = 4000
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
