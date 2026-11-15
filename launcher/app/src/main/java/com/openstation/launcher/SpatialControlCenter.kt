package com.openstation.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Pure presentation helpers for Control Center tiles & QR modal (JVM-testable).
 */
object ControlCenterLabels {
    fun ringLabel(mode: String): String = when (mode) {
        "rainbow" -> "Радуга"
        "solid" -> "Свет"
        "breathe" -> "Дыхание"
        "spinner" -> "Круг"
        "spectrum", "music" -> "Спектр"
        "off" -> "Выкл"
        else -> mode
    }

    fun routeLabel(route: String): String = when (route) {
        "speakers" -> "Колонка"
        "headphones" -> "Наушники"
        "hdmi" -> "HDMI"
        "both" -> "Вместе"
        else -> "Авто"
    }

    fun eqLabel(preset: String): String = when (preset) {
        "bass_boost" -> "Бас+"
        "vocal" -> "Вокал"
        "night" -> "Ночь"
        "rock" -> "Рок"
        "electronic" -> "Клуб"
        "flat" -> "Flat"
        "custom" -> "Кастом"
        else -> "Баланс"
    }
}

/**
 * Slide-over Spatial Glass Control Center.
 * Consolidates all hardware toggles, audio/DSP, timers, BLE remote, Web QR, OTA,
 * customization, and Android TV settings into one sleek frosted drawer off the Home canvas.
 */
class SpatialControlCenter(
    private val context: Context,
    private val backdrop: SpatialGlassBackdropView,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCycleRing()
        fun onToggleMic()
        fun onCycleRoute()
        fun onCycleEq()
        fun onCycleVisualizer()
        fun onToggleCec()
        fun onOpenMusicScreen()
        fun onMusicPlayPause()
        fun onMusicNext()
        fun onOpenPersona()
        fun onOpenHardwareEq()
        fun onOpenTimers()
        fun onOpenRemoteSetup()
        fun onOpenWebQr()
        fun onOpenCustomization()
        fun onOpenOta()
        fun onOpenWizard()
        fun onOpenShellReset()
        fun onOpenAndroidSettingsMenu()
        fun onAnimationSpeedChanged(speed: MotionPrefs.Speed) {}
        fun onUiStyleChanged(style: LiquidGlassKit.UiStyle) {}
        fun onClosed()
    }

    private data class ToggleCard(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val sub: TextView
    )

    private data class ActionRow(
        val root: LinearLayout,
        val title: TextView,
        val value: TextView
    )

    private class AccordionSection(
        val index: Int,
        val container: LinearLayout,
        val header: LinearLayout,
        val summary: TextView,
        val chevron: ImageView,
        val body: LinearLayout,
        var expanded: Boolean,
        var animator: ValueAnimator? = null
    )

    val rootOverlay = FrameLayout(context).apply {
        visibility = View.GONE
        alpha = 0f
        isClickable = true
        isFocusable = false
        elevation = dp(28).toFloat()
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val easeOutCubic get() = MotionPrefs.easeOutCurve
    private val easeInOutCurve get() = MotionPrefs.easeInOutCurve
    private val focusSpring get() = MotionPrefs.focusCurve

    private val drawerCard: LinearLayout
    private val scrollView: ScrollView
    private val contentCol: LinearLayout
    private val tvTelemetry: TextView
    private val musicMiniCard: LinearLayout
    private val ivMiniCover: ImageView
    private val tvMiniTitle: TextView
    private val tvMiniSub: TextView
    private val btnMiniPlay: FrameLayout
    private val ivMiniPlay: ImageView
    private val btnMiniNext: FrameLayout

    private val tileMic: ToggleCard
    private val tileRoute: ToggleCard
    private val tileEq: ToggleCard
    private val tileRing: ToggleCard
    private val tileVis: ToggleCard
    private val tileCec: ToggleCard

    private val rowPersona: ActionRow
    private val rowDsp: ActionRow
    private val rowTimers: ActionRow
    private val rowRemote: ActionRow
    private val rowWebQr: ActionRow
    private val rowCustom: ActionRow
    private val rowUiStyle: ActionRow
    private lateinit var rowAnimSpeed: ActionRow
    private val rowOta: ActionRow
    private val rowWizard: ActionRow
    private val rowShellReset: ActionRow
    private val rowSystemTv: ActionRow

    private val lensCleanup = ArrayList<() -> Unit>()
    private val gridCols = 3
    private val toggleCards = ArrayList<ToggleCard>(6)
    private val actionRows = ArrayList<ActionRow>(11)
    private val sections = ArrayList<AccordionSection>(4)
    private val sectionRowsMap = HashMap<Int, List<ActionRow>>()
    private var expandedSectionIdx = 0

    var isOpen = false
        private set

    init {
        rootOverlay.setOnClickListener { close() }

        drawerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = false
            setPadding(dp(14), dp(18), dp(14), dp(16))
            isClickable = true
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                dp(416),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(18)
                marginEnd = dp(18)
            }
        }
        lensCleanup.add(
            backdrop.registerLens(
                drawerCard,
                SpatialLensSpec(
                    radiusDp = 28f,
                    baseTint = Color.parseColor("#A4070B16"),
                    focusTint = Color.parseColor("#A4070B16"),
                    bevelDp = 18f,
                    refractDp = 12f,
                    chromaDp = 2.0f,
                    shadowDp = 22f,
                    rimAlpha = 0.36f,
                    focusRimAlpha = 0.36f,
                    blurMix = 0.96f
                )
            )
        )

        // Header
        val tvTitle = TextView(context).apply {
            text = "Пункт управления"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = YandexFonts.displayBold(context)
            includeFontPadding = false
        }
        tvTelemetry = TextView(context).apply {
            text = "● Готова"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            typeface = YandexFonts.textMedium(context)
            setPadding(0, dp(3), 0, dp(12))
        }
        drawerCard.addView(tvTitle)
        drawerCard.addView(tvTelemetry)

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = true
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(4)
            }
        }
        contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }

        // Compact Now Playing Mini Capsule
        musicMiniCard = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                close(immediate = false)
                callbacks.onOpenMusicScreen()
            }
        }
        wireFocusPop(musicMiniCard, 1.02f)
        musicMiniCard.background = makeMonolithSurface(
            radiusDp = 18f,
            normalFill = Color.parseColor("#2E0E1729"),
            focusFill = Color.parseColor("#541A2E50")
        )

        ivMiniCover = ImageView(context).apply {
            setSpriteDrawable(R.drawable.sprite_ym_emblem)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#182235"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(10)
            }
        }
        val miniMeta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvMiniTitle = TextView(context).apply {
            text = "Моя волна"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = YandexFonts.displayBold(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvMiniSub = TextView(context).apply {
            text = "Открыть плеер"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        miniMeta.addView(tvMiniTitle)
        miniMeta.addView(tvMiniSub)

        ivMiniPlay = ImageView(context).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
        }
        btnMiniPlay = makeMiniOrbBtn(ivMiniPlay) { callbacks.onMusicPlayPause() }
        val ivNext = ImageView(context).apply {
            setImageResource(R.drawable.ic_next)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER)
        }
        btnMiniNext = makeMiniOrbBtn(ivNext) { callbacks.onMusicNext() }

        musicMiniCard.addView(ivMiniCover)
        musicMiniCard.addView(miniMeta)
        musicMiniCard.addView(btnMiniPlay)
        musicMiniCard.addView(btnMiniNext)
        contentCol.addView(musicMiniCard)

        // Sub-Tab 0: Quick Toggles (3x2 Hardware Grid)
        val togglesGrid = GridLayout(context).apply {
            columnCount = gridCols
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tileMic = makeToggleCard(R.drawable.ic_hw_mic_on, "Микрофон", "Вкл") { callbacks.onToggleMic() }
        tileRoute = makeToggleCard(R.drawable.ic_hw_speaker, "Выход", "Авто") { callbacks.onCycleRoute() }
        tileEq = makeToggleCard(R.drawable.ic_hw_eq, "Пресет", "Баланс") { callbacks.onCycleEq() }
        tileRing = makeToggleCard(R.drawable.ic_hw_ring, "Кольцо", "Спектр") { callbacks.onCycleRing() }
        tileVis = makeToggleCard(R.drawable.ic_wave, "Экран", "Волна") { callbacks.onCycleVisualizer() }
        tileCec = makeToggleCard(R.drawable.ic_hw_tv, "ТВ", "CEC") { callbacks.onToggleCec() }

        toggleCards.addAll(listOf(tileMic, tileRoute, tileEq, tileRing, tileVis, tileCec))
        toggleCards.forEachIndexed { idx, card ->
            val col = idx % gridCols
            val row = idx / gridCols
            val gp = GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(col, 1f)
            ).apply {
                width = 0
                height = dp(72)
                if (col < gridCols - 1) marginEnd = dp(8)
                if (row == 0) bottomMargin = dp(8)
            }
            togglesGrid.addView(card.root, gp)
        }

        // Sub-Tabs Rows
        rowPersona = makeActionRow(R.drawable.ic_wave, "Ассистент и голос", "Алиса") { callbacks.onOpenPersona() }
        rowTimers = makeActionRow(R.drawable.ic_refresh, "Таймеры и будильники", "") { callbacks.onOpenTimers() }
        rowRemote = makeActionRow(R.drawable.ic_hw_bluetooth, "Пульт ДУ (BLE)", "") { callbacks.onOpenRemoteSetup() }

        rowDsp = makeActionRow(R.drawable.ic_hw_eq, "Звук и DSP", "SY6045S") { callbacks.onOpenHardwareEq() }
        rowCustom = makeActionRow(R.drawable.ic_hw_display, "Обои и интерфейс", "") { callbacks.onOpenCustomization() }
        rowUiStyle = makeActionRow(R.drawable.ic_hw_display, "Тема лаунчера", LiquidGlassKit.currentUiStyle.label) {
            val next = if (LiquidGlassKit.isClassicUi()) LiquidGlassKit.UiStyle.FROSTED else LiquidGlassKit.UiStyle.CLASSIC
            LiquidGlassKit.setUiStyle(context, next, persist = true)
            updateUiStyleUi()
            callbacks.onUiStyleChanged(next)
        }

        val initSpeed = MotionPrefs.current(context)
        rowAnimSpeed = makeActionRow(R.drawable.ic_refresh, "Скорость анимаций", initSpeed.label) {
            val next = MotionPrefs.cycle(context)
            updateAnimSpeedUi(next)
            callbacks.onAnimationSpeedChanged(next)
            rowAnimSpeed.root.animate()
                .scaleX(1.045f).scaleY(1.045f)
                .setDuration(MotionPrefs.dur(context, 200L))
                .setInterpolator(MotionPrefs.easeOutCurve)
                .withEndAction {
                    rowAnimSpeed.root.animate()
                        .scaleX(if (rowAnimSpeed.root.isFocused) 1.02f else 1f)
                        .scaleY(if (rowAnimSpeed.root.isFocused) 1.02f else 1f)
                        .setDuration(MotionPrefs.dur(context, 260L))
                        .setInterpolator(MotionPrefs.easeOutCurve)
                        .start()
                }
                .start()
        }
        rowOta = makeActionRow(R.drawable.ic_open_external, "Обновление прошивки", "v1.6.3") { callbacks.onOpenOta() }
        rowWebQr = makeActionRow(R.drawable.ic_hw_wifi, "Веб-пульт на телефоне", "QR") { callbacks.onOpenWebQr() }
        rowWizard = makeActionRow(R.drawable.ic_hw_info, "Мастер настройки", "") { callbacks.onOpenWizard() }
        rowShellReset = makeActionRow(R.drawable.ic_refresh, "Сброс оболочки OpenStation", "▸") { callbacks.onOpenShellReset() }
        rowShellReset.title.setTextColor(Color.parseColor("#FDA4AF"))
        rowShellReset.value.setTextColor(Color.parseColor("#FCA5A5"))
        rowSystemTv = makeActionRow(R.drawable.ic_settings, "Настройки Android TV", "▸") { callbacks.onOpenAndroidSettingsMenu() }

        actionRows.addAll(
            listOf(
                rowPersona, rowTimers, rowRemote,
                rowDsp, rowUiStyle, rowCustom,
                rowAnimSpeed, rowOta, rowWebQr, rowWizard, rowShellReset, rowSystemTv
            )
        )

        sectionRowsMap[1] = listOf(rowPersona, rowTimers, rowRemote)
        sectionRowsMap[2] = listOf(rowUiStyle, rowDsp, rowCustom)
        sectionRowsMap[3] = listOf(rowAnimSpeed, rowOta, rowWebQr, rowWizard, rowShellReset, rowSystemTv)

        val sec0 = buildAccordionSection(
            index = 0,
            iconRes = R.drawable.ic_hw_chip,
            title = "Быстрое управление",
            summaryText = "6 тумблеров",
            expanded = true,
            children = listOf(togglesGrid)
        )
        val sec1 = buildAccordionSection(
            index = 1,
            iconRes = R.drawable.ic_wave,
            title = "Ассистент и устройства",
            summaryText = "Алиса",
            expanded = false,
            children = sectionRowsMap[1]!!.map { it.root }
        )
        val sec2 = buildAccordionSection(
            index = 2,
            iconRes = R.drawable.ic_hw_eq,
            title = "Звук и оформление",
            summaryText = "DSP · Обои",
            expanded = false,
            children = sectionRowsMap[2]!!.map { it.root }
        )
        val sec3 = buildAccordionSection(
            index = 3,
            iconRes = R.drawable.ic_settings,
            title = "Система и анимации",
            summaryText = initSpeed.shortLabel,
            expanded = false,
            children = sectionRowsMap[3]!!.map { it.root }
        )

        sections.addAll(listOf(sec0, sec1, sec2, sec3))
        sections.forEach { contentCol.addView(it.container) }

        wireFocusGraph()

        scrollView.addView(contentCol)
        drawerCard.addView(scrollView)
        rootOverlay.addView(drawerCard)
    }

    private fun buildAccordionSection(
        index: Int,
        iconRes: Int,
        title: String,
        summaryText: String,
        expanded: Boolean,
        children: List<View>
    ): AccordionSection {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = false
            background = makeMonolithSurface(
                radiusDp = 20f,
                normalFill = Color.parseColor("#280C1426"),
                focusFill = Color.parseColor("#280C1426"),
                normalStroke = Color.parseColor("#22FFFFFF"),
                focusStroke = Color.parseColor("#22FFFFFF")
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        lateinit var secRef: AccordionSection

        val header = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            isFocusable = true
            isClickable = true
            background = makeMonolithSurface(
                radiusDp = 18f,
                normalFill = Color.TRANSPARENT,
                focusFill = Color.parseColor("#541A2E52"),
                normalStroke = Color.TRANSPARENT,
                focusStroke = Color.parseColor("#B893C5FD")
            )
            setOnClickListener { toggleAccordionSection(index) }
        }
        wireFocusPop(header, 1.015f)

        val ivIcon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#93C5FD"))
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                marginEnd = dp(10)
            }
        }
        val tvHeaderTitle = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = YandexFonts.displayBold(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSummary = TextView(context).apply {
            text = summaryText
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 11f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            alpha = if (expanded) 0f else 1f
            setPadding(dp(6), 0, dp(8), 0)
        }
        val ivChevron = makeChevronView(expanded)

        header.addView(ivIcon)
        header.addView(tvHeaderTitle)
        header.addView(tvSummary)
        header.addView(ivChevron)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
            visibility = if (expanded) View.VISIBLE else View.GONE
            alpha = if (expanded) 1f else 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            )
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(9), dp(4), dp(9), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        children.forEach { inner.addView(it) }
        body.addView(inner)

        container.addView(header)
        container.addView(body)

        secRef = AccordionSection(index, container, header, tvSummary, ivChevron, body, expanded)
        return secRef
    }

    private fun makeChevronView(expanded: Boolean): ImageView {
        val w = dp(14)
        val h = dp(14)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat().coerceAtLeast(2.2f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = Color.parseColor("#CBD5E1")
        }
        val path = android.graphics.Path().apply {
            moveTo(w * 0.24f, h * 0.36f)
            lineTo(w * 0.50f, h * 0.66f)
            lineTo(w * 0.76f, h * 0.36f)
        }
        c.drawPath(path, p)
        return ImageView(context).apply {
            setImageBitmap(bmp)
            rotation = if (expanded) 0f else -90f
            layoutParams = LinearLayout.LayoutParams(w, h)
        }
    }

    private fun toggleAccordionSection(targetIdx: Int) {
        val nextExpanded = if (expandedSectionIdx == targetIdx) -1 else targetIdx
        expandedSectionIdx = nextExpanded
        sections.forEach { sec ->
            val shouldExpand = sec.index == nextExpanded
            animateAccordionSection(sec, shouldExpand)
        }
        wireFocusGraph()
        if (nextExpanded >= 0) {
            val targetSec = sections[nextExpanded]
            targetSec.container.postDelayed({
                if (isOpen && expandedSectionIdx == nextExpanded) {
                    smoothScrollSectionIntoView(targetSec)
                }
            }, MotionPrefs.dur(context, 140L))
        }
    }

    private fun syncAccordionTranslations() {
        var cumulativeDelta = 0f
        for (i in 0 until contentCol.childCount) {
            val child = contentCol.getChildAt(i)
            child.translationY = cumulativeDelta
            val sec = sections.firstOrNull { it.container === child } ?: continue
            val layoutH = if (sec.body.visibility == View.GONE) 0 else (sec.body.layoutParams?.height ?: 0).coerceAtLeast(0)
            val clipH = sec.body.clipBounds?.height() ?: layoutH
            if (sec.animator != null) {
                cumulativeDelta += (clipH - layoutH).toFloat()
            }
        }
    }

    private fun animateAccordionSection(sec: AccordionSection, expand: Boolean) {
        if (sec.expanded == expand && sec.animator == null) return
        sec.expanded = expand
        sec.animator?.cancel()
        sec.animator = null

        val animDur = MotionPrefs.dur(context, if (expand) 440L else 380L)

        sec.chevron.animate().cancel()
        sec.chevron.animate()
            .rotation(if (expand) 0f else -90f)
            .setDuration(animDur)
            .setInterpolator(easeInOutCurve)
            .start()

        sec.summary.animate().cancel()
        sec.summary.animate()
            .alpha(if (expand) 0f else 1f)
            .setDuration(MotionPrefs.dur(context, 280L))
            .setInterpolator(easeOutCubic)
            .start()

        val inner = sec.body.getChildAt(0) ?: return
        val availW = (scrollView.width - contentCol.paddingLeft - contentCol.paddingRight).coerceAtLeast(dp(340))
        val curH = sec.body.clipBounds?.height() ?: if (sec.body.visibility == View.GONE) 0 else sec.body.height.coerceAtLeast(0)
        val clipRect = android.graphics.Rect()

        if (expand) {
            sec.body.measure(
                View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetH = sec.body.measuredHeight.coerceAtLeast(dp(48))
            val startAlpha = if (curH == 0) 0f else inner.alpha
            // Set full targetH once upfront so scrolling/layout changes happen once, not per frame
            sec.body.layoutParams = (sec.body.layoutParams as LinearLayout.LayoutParams).apply {
                height = targetH
            }
            sec.body.visibility = View.VISIBLE
            sec.body.alpha = 1f
            clipRect.set(0, 0, availW, curH)
            sec.body.clipBounds = clipRect
            inner.alpha = startAlpha
            inner.translationY = if (curH == 0) -dp(12).toFloat() else 0f

            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = animDur
                interpolator = easeInOutCurve
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    val h = (curH + (targetH - curH) * t).toInt().coerceIn(0, targetH)
                    clipRect.set(0, 0, availW, h)
                    sec.body.clipBounds = clipRect
                    inner.alpha = startAlpha + (1f - startAlpha) * t
                    inner.translationY = -dp(10) * (1f - t)
                    syncAccordionTranslations()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        sec.animator = null
                        if (sec.expanded) {
                            sec.body.clipBounds = null
                            sec.body.layoutParams = (sec.body.layoutParams as LinearLayout.LayoutParams).apply {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            sec.body.alpha = 1f
                            inner.alpha = 1f
                            inner.translationY = 0f
                        }
                        syncAccordionTranslations()
                    }
                })
            }
            sec.animator = anim
            syncAccordionTranslations()
            anim.start()
        } else {
            val startH = if (curH > 0) curH else {
                sec.body.measure(
                    View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                sec.body.measuredHeight
            }
            val layoutH = (sec.body.layoutParams?.height ?: 0).let { lpH ->
                if (lpH > 0) lpH else startH
            }
            if (startH <= 0) {
                sec.body.clipBounds = null
                sec.body.visibility = View.GONE
                sec.body.layoutParams = (sec.body.layoutParams as LinearLayout.LayoutParams).apply { height = 0 }
                inner.alpha = 0f
                inner.translationY = 0f
                syncAccordionTranslations()
                return
            }
            if (sec.body.layoutParams?.height != layoutH) {
                sec.body.layoutParams = (sec.body.layoutParams as LinearLayout.LayoutParams).apply { height = layoutH }
            }
            val startAlpha = inner.alpha
            clipRect.set(0, 0, availW, startH)
            sec.body.clipBounds = clipRect
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = animDur
                interpolator = easeInOutCurve
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    val h = (startH * (1f - t)).toInt().coerceIn(0, layoutH)
                    clipRect.set(0, 0, availW, h)
                    sec.body.clipBounds = clipRect
                    inner.alpha = startAlpha * (1f - t)
                    inner.translationY = -dp(8) * t
                    syncAccordionTranslations()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        sec.animator = null
                        if (!sec.expanded) {
                            sec.body.clipBounds = null
                            sec.body.visibility = View.GONE
                            sec.body.layoutParams = (sec.body.layoutParams as LinearLayout.LayoutParams).apply {
                                height = 0
                            }
                            inner.alpha = 0f
                            inner.translationY = 0f
                        }
                        syncAccordionTranslations()
                    }
                })
            }
            sec.animator = anim
            syncAccordionTranslations()
            anim.start()
        }
    }

    private fun smoothScrollSectionIntoView(sec: AccordionSection) {
        val svH = scrollView.height
        val colH = contentCol.height
        if (svH <= 0 || colH <= svH) return
        val maxScroll = (colH - svH).coerceAtLeast(0)
        val top = sec.container.top
        val bottom = sec.container.bottom
        val curY = scrollView.scrollY
        val targetY = when {
            top < curY -> top - dp(8)
            bottom > curY + svH -> minOf(top - dp(8), bottom - svH + dp(12))
            else -> return
        }.coerceIn(0, maxScroll)
        ObjectAnimator.ofInt(scrollView, "scrollY", curY, targetY).apply {
            duration = MotionPrefs.dur(context, 420L)
            interpolator = easeInOutCurve
            start()
        }
    }

    private fun updateAnimSpeedUi(speed: MotionPrefs.Speed) {
        rowAnimSpeed.value.text = speed.label
        sections.getOrNull(3)?.summary?.text = speed.shortLabel
    }

    fun syncUiStyle(style: LiquidGlassKit.UiStyle = LiquidGlassKit.currentUiStyle) {
        rowUiStyle.value.text = style.label
        LiquidGlassKit.refreshGlassTree(rootOverlay)
    }

    private fun updateUiStyleUi() {
        val style = LiquidGlassKit.currentUiStyle
        rowUiStyle.value.text = style.label
    }

    private fun makeMonolithSurface(
        radiusDp: Float,
        normalFill: Int,
        focusFill: Int,
        normalStroke: Int = Color.parseColor("#24FFFFFF"),
        focusStroke: Int = Color.parseColor("#AA93C5FD")
    ): StateListDrawable {
        val rad = dp(1) * radiusDp
        val strokeW = maxOf(1, dp(1))
        val focused = GradientDrawable().apply {
            cornerRadius = rad
            setColor(focusFill)
            setStroke(maxOf(2, dp(1)), focusStroke)
        }
        val normal = GradientDrawable().apply {
            cornerRadius = rad
            setColor(normalFill)
            setStroke(strokeW, normalStroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun makeMiniOrbBtn(iconView: ImageView, onClick: () -> Unit): FrameLayout {
        val btn = FrameLayout(context).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            background = makeMonolithSurface(
                radiusDp = 999f,
                normalFill = Color.parseColor("#36152238"),
                focusFill = Color.parseColor("#70244272")
            )
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(6)
            }
            setOnClickListener { onClick() }
        }
        btn.addView(iconView)
        wireFocusPop(btn, 1.07f)
        return btn
    }

    private fun makeToggleCard(
        iconRes: Int,
        titleText: String,
        initialSub: String,
        onClick: () -> Unit
    ): ToggleCard {
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true
            isClickable = true
            background = makeMonolithSurface(
                radiusDp = 18f,
                normalFill = Color.parseColor("#420D1526"),
                focusFill = Color.parseColor("#6A1B335C")
            )
            setOnClickListener { onClick() }
        }
        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                bottomMargin = dp(5)
            }
        }
        val tvTitle = TextView(context).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = YandexFonts.displayBold(context)
            maxLines = 1
            includeFontPadding = false
        }
        val tvSub = TextView(context).apply {
            text = initialSub
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        root.addView(iv)
        root.addView(tvTitle)
        root.addView(tvSub)
        wireFocusPop(root, 1.045f)
        return ToggleCard(root, iv, tvTitle, tvSub)
    }

    private fun makeActionRow(
        iconRes: Int,
        label: String,
        initialVal: String,
        onClick: () -> Unit
    ): ActionRow {
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isFocusable = true
            isClickable = true
            background = makeMonolithSurface(
                radiusDp = 15f,
                normalFill = Color.parseColor("#2A0C1424"),
                focusFill = Color.parseColor("#5C1B335C")
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#CBD5E1"))
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                marginEnd = dp(10)
            }
        }
        val tvTitle = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12.5f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvVal = TextView(context).apply {
            text = initialVal
            setTextColor(Color.parseColor("#7DD3FC"))
            textSize = 11.5f
            typeface = YandexFonts.textMedium(context)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        root.addView(iv)
        root.addView(tvTitle)
        root.addView(tvVal)
        wireFocusPop(root, 1.02f)
        return ActionRow(root, tvTitle, tvVal)
    }

    private fun wireFocusPop(v: View, scaleTarget: Float) {
        v.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) scaleTarget else 1f)
                .scaleY(if (hasFocus) scaleTarget else 1f)
                .setDuration(MotionPrefs.dur(context, if (hasFocus) 230L else 190L))
                .setInterpolator(if (hasFocus) focusSpring else easeOutCubic)
                .start()
        }
    }

    private fun firstFocusableInSection(idx: Int): Int? {
        if (idx < 0 || idx > sections.lastIndex) return null
        val sec = sections[idx]
        if (!sec.expanded) return null
        return if (idx == 0) {
            tileMic.root.id
        } else {
            sectionRowsMap[idx]?.firstOrNull { it.root.visibility == View.VISIBLE }?.root?.id
        }
    }

    private fun lastFocusableInSection(idx: Int): Int? {
        if (idx < 0 || idx > sections.lastIndex) return null
        val sec = sections[idx]
        if (!sec.expanded) return null
        return if (idx == 0) {
            tileRing.root.id
        } else {
            sectionRowsMap[idx]?.lastOrNull { it.root.visibility == View.VISIBLE }?.root?.id
        }
    }

    private fun wireFocusGraph() {
        val sec0HeaderId = sections.getOrNull(0)?.header?.id ?: tileMic.root.id
        val sec1HeaderId = sections.getOrNull(1)?.header?.id ?: sec0HeaderId

        musicMiniCard.nextFocusUpId = musicMiniCard.id
        musicMiniCard.nextFocusRightId = btnMiniPlay.id
        musicMiniCard.nextFocusDownId = sec0HeaderId

        btnMiniPlay.nextFocusUpId = btnMiniPlay.id
        btnMiniPlay.nextFocusLeftId = musicMiniCard.id
        btnMiniPlay.nextFocusRightId = btnMiniNext.id
        btnMiniPlay.nextFocusDownId = sec0HeaderId

        btnMiniNext.nextFocusUpId = btnMiniNext.id
        btnMiniNext.nextFocusLeftId = btnMiniPlay.id
        btnMiniNext.nextFocusRightId = btnMiniNext.id
        btnMiniNext.nextFocusDownId = sec0HeaderId

        for (i in toggleCards.indices) {
            val c = toggleCards[i].root
            val row = i / gridCols
            val col = i % gridCols
            c.nextFocusUpId = if (row == 0) sec0HeaderId else toggleCards[i - gridCols].root.id
            c.nextFocusDownId = if (row == 0) toggleCards[i + gridCols].root.id else sec1HeaderId
            c.nextFocusLeftId = if (col > 0) toggleCards[i - 1].root.id else c.id
            c.nextFocusRightId = if (col < gridCols - 1) toggleCards[i + 1].root.id else c.id
        }

        sections.forEachIndexed { idx, sec ->
            val hdr = sec.header
            hdr.nextFocusLeftId = hdr.id
            hdr.nextFocusRightId = hdr.id
            hdr.nextFocusUpId = if (idx == 0) {
                musicMiniCard.id
            } else {
                lastFocusableInSection(idx - 1) ?: sections[idx - 1].header.id
            }
            hdr.nextFocusDownId = firstFocusableInSection(idx)
                ?: sections.getOrNull(idx + 1)?.header?.id
                ?: hdr.id
        }

        rebuildActionRowFocusLinks()
    }

    private fun rebuildActionRowFocusLinks() {
        sections.forEachIndexed { secIdx, sec ->
            if (secIdx == 0) return@forEachIndexed
            val visibleRows = sectionRowsMap[secIdx]?.filter { it.root.visibility == View.VISIBLE }.orEmpty()
            val nextHeaderId = sections.getOrNull(secIdx + 1)?.header?.id
            visibleRows.forEachIndexed { rIdx, row ->
                row.root.nextFocusLeftId = row.root.id
                row.root.nextFocusRightId = row.root.id
                row.root.nextFocusUpId = if (rIdx == 0) sec.header.id else visibleRows[rIdx - 1].root.id
                row.root.nextFocusDownId = when {
                    rIdx < visibleRows.lastIndex -> visibleRows[rIdx + 1].root.id
                    nextHeaderId != null -> nextHeaderId
                    else -> row.root.id
                }
            }
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        rootOverlay.animate().cancel()
        drawerCard.animate().cancel()
        updateAnimSpeedUi(MotionPrefs.current(context))
        updateUiStyleUi()

        rootOverlay.visibility = View.VISIBLE
        rootOverlay.alpha = 0f
        drawerCard.translationX = dp(460).toFloat()
        drawerCard.alpha = 1f
        scrollView.scrollTo(0, 0)

        val openDur = MotionPrefs.dur(context, 500L)
        rootOverlay.animate()
            .alpha(1f)
            .setDuration(openDur)
            .setInterpolator(easeOutCubic)
            .start()
        drawerCard.animate()
            .translationX(0f)
            .setDuration(openDur)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (isOpen) {
                    val focusTarget = when {
                        expandedSectionIdx == 0 -> tileMic.root
                        expandedSectionIdx in sections.indices -> sections[expandedSectionIdx].header
                        else -> sections.firstOrNull()?.header ?: musicMiniCard
                    }
                    focusTarget.requestFocus()
                }
            }
            .start()
    }

    fun close(immediate: Boolean = false) {
        if (!isOpen && rootOverlay.visibility != View.VISIBLE) return
        isOpen = false
        rootOverlay.animate().cancel()
        drawerCard.animate().cancel()
        if (immediate) {
            rootOverlay.visibility = View.GONE
            rootOverlay.alpha = 0f
            drawerCard.translationX = 0f
            callbacks.onClosed()
            return
        }
        val closeDur = MotionPrefs.dur(context, 420L)
        drawerCard.animate()
            .translationX(dp(460).toFloat())
            .setDuration(closeDur)
            .setInterpolator(easeOutCubic)
            .start()
        rootOverlay.animate()
            .alpha(0f)
            .setDuration(closeDur)
            .setInterpolator(easeOutCubic)
            .withEndAction {
                if (!isOpen) {
                    rootOverlay.visibility = View.GONE
                    drawerCard.translationX = 0f
                }
            }
            .start()
        callbacks.onClosed()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isOpen || event.action != KeyEvent.ACTION_DOWN) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                close()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val cur = rootOverlay.findFocus()
                val leftEdge = (cur === musicMiniCard ||
                    cur === tileMic.root ||
                    cur === tileRing.root ||
                    sections.any { it.header === cur } ||
                    actionRows.any { it.root === cur })
                if (leftEdge || cur == null) {
                    close()
                    return true
                }
            }
        }
        return false
    }

    fun syncState(
        telemetry: String,
        micMuted: Boolean,
        ringMode: String,
        outputRoute: String,
        eqPreset: String,
        visualizerStyle: String,
        visualizerLabel: String,
        cecStandby: Boolean,
        assistantName: String,
        hwEqSupported: Boolean,
        hwEqProfile: String,
        timersCountText: String,
        remoteConnectedText: String,
        ipLine: String,
        fwVer: String,
        otaJson: JSONObject?
    ) {
        tvTelemetry.text = telemetry

        tileMic.sub.text = if (micMuted) "Выкл" else "Вкл"
        tileMic.icon.setImageResource(if (micMuted) R.drawable.ic_hw_mic_off else R.drawable.ic_hw_mic_on)
        tileMic.sub.setTextColor(Color.parseColor(if (micMuted) "#FDA4AF" else "#86EFAC"))
        val micFill = if (micMuted) Color.parseColor("#884C1D24") else Color.parseColor("#420D1526")
        tileMic.root.background = makeMonolithSurface(
            radiusDp = 18f,
            normalFill = micFill,
            focusFill = if (micMuted) Color.parseColor("#AA7F1D2C") else Color.parseColor("#6A1B335C")
        )

        val routeTxt = ControlCenterLabels.routeLabel(outputRoute)
        val eqTxt = ControlCenterLabels.eqLabel(eqPreset)
        val ringTxt = ControlCenterLabels.ringLabel(ringMode)
        tileRoute.sub.text = routeTxt
        tileEq.sub.text = eqTxt
        tileRing.sub.text = ringTxt
        tileVis.sub.text = visualizerLabel.ifBlank { visualizerStyle }
        tileCec.sub.text = if (cecStandby) "Выкл" else "Вкл"

        rowPersona.value.text = assistantName
        val prevDspVis = rowDsp.root.visibility
        rowDsp.root.visibility = if (hwEqSupported) View.VISIBLE else View.GONE
        rowDsp.value.text = hwEqProfile
        if (prevDspVis != rowDsp.root.visibility) {
            wireFocusGraph()
        }
        rowTimers.value.text = timersCountText
        rowRemote.value.text = remoteConnectedText
        rowWebQr.value.text = ipLine

        updateAnimSpeedUi(MotionPrefs.current(context))
        updateUiStyleUi()
        sections.getOrNull(0)?.summary?.text = "$routeTxt · $ringTxt"
        sections.getOrNull(1)?.summary?.text = if (timersCountText != "Нет активных" && timersCountText.isNotBlank()) {
            "$assistantName · $timersCountText"
        } else {
            assistantName
        }
        val styleShort = if (LiquidGlassKit.isClassicUi()) "Классика" else "Матовое"
        sections.getOrNull(2)?.summary?.text = if (hwEqSupported) "$styleShort · $hwEqProfile" else styleShort

        val avail = otaJson?.optBoolean("updateAvailable", false) ?: false
        val busy = otaJson?.optBoolean("busy", false) ?: false
        val pct = otaJson?.optInt("progressPct", 0) ?: 0
        val latVer = otaJson?.optString("latestVersion", "") ?: ""
        when {
            busy -> {
                rowOta.value.text = "$pct%"
                rowOta.value.setTextColor(Color.parseColor("#38BDF8"))
            }
            avail -> {
                rowOta.value.text = if (latVer.isNotBlank()) "v$latVer" else "Доступно"
                rowOta.value.setTextColor(Color.parseColor("#FBBF24"))
            }
            else -> {
                rowOta.value.text = "v$fwVer"
                rowOta.value.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    fun syncMusic(snap: YmPlayerSnapshot, coverBmp: android.graphics.Bitmap?) {
        val tr = snap.currentTrack
        if (tr != null) {
            tvMiniTitle.text = tr.title
            tvMiniSub.text = tr.artists
        } else {
            tvMiniTitle.text = "Моя волна"
            tvMiniSub.text = "Включить поток"
        }
        ivMiniPlay.setImageResource(
            when {
                snap.buffering -> R.drawable.ic_refresh
                snap.playing -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
        )
        if (coverBmp != null) {
            ivMiniCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivMiniCover.setImageBitmap(coverBmp)
        } else {
            ivMiniCover.scaleType = ImageView.ScaleType.CENTER_CROP
            ivMiniCover.setSpriteDrawable(R.drawable.sprite_ym_emblem)
        }
    }

    fun release() {
        sections.forEach {
            it.animator?.cancel()
            it.animator = null
        }
        lensCleanup.forEach { it() }
        lensCleanup.clear()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
