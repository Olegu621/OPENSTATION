package com.openstation.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.openstation.launcher.market.CatalogSource
import com.openstation.launcher.market.InstallStageEvent
import com.openstation.launcher.market.InstalledAppEntry
import com.openstation.launcher.market.MarketApp
import com.openstation.launcher.market.MarketCatalog
import com.openstation.launcher.market.MarketImageLoader
import com.openstation.launcher.market.MarketInstaller
import com.openstation.launcher.market.MarketPrefs
import com.openstation.launcher.market.MarketRepository
import com.openstation.launcher.market.MarketUiKit
import com.openstation.launcher.market.MarketUiKit.PillStyle
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MarketActivity : Activity() {

    enum class MarketTab {
        CATALOG,
        UPDATES,
        INSTALLED,
        LOCAL_APK,
        SETTINGS
    }

    companion object {
        private const val REQ_STORAGE_PERM = 4021
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inspectExecutor = Executors.newFixedThreadPool(2)

    private lateinit var rootFrame: FrameLayout
    private lateinit var tvDaemonPill: TextView
    private lateinit var tvCatalogSourceHint: TextView
    private val tabButtons = LinkedHashMap<MarketTab, TextView>()
    private lateinit var tabStage: FrameLayout
    private lateinit var btnRefreshTop: TextView

    // Overlays
    private lateinit var modalScrim: FrameLayout
    private var lastModalAnchor: View? = null
    private var activeTaskCancel: AtomicBoolean? = null
    private var activeTaskRunning = false

    // State
    private var currentTab = MarketTab.CATALOG
    private var selectedCategory = "Все"
    private var showSystemInstalled = false
    private var daemonRootAlive = false
    private var catalogLoadedOnce = false
    private var catalog: MarketCatalog? = null
    private var installedApps: List<InstalledAppEntry> = emptyList()
    private var currentBrowseDir: File = resolveDefaultApkDir()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this)
        if (Theme.forwardIfGlass(this, com.openstation.launcher.glass.MarketActivity::class.java)) return
        window.setBackgroundDrawable(null)
        StationAudioService.ensureStarted(this)
        buildRootUi()
        switchTab(MarketTab.CATALOG)
        probeDaemonAndLoadCatalog(forceNetwork = false)
    }

    override fun onResume() {
        super.onResume()
        StationAudioService.ensureStarted(this)
        refreshInstalledListAsync()
        if (currentTab == MarketTab.LOCAL_APK || currentTab == MarketTab.SETTINGS) {
            renderActiveTab()
        }
    }

    override fun onDestroy() {
        inspectExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (handleBackPressed()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBackPressed()) {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERM && currentTab == MarketTab.LOCAL_APK) {
            renderActiveTab()
        }
    }

    private fun handleBackPressed(): Boolean {
        if (modalScrim.visibility == View.VISIBLE) {
            if (activeTaskRunning) {
                activeTaskCancel?.set(true)
            } else {
                closeModal()
            }
            return true
        }
        if (currentTab == MarketTab.LOCAL_APK && canNavigateUpDir()) {
            currentBrowseDir = currentBrowseDir.parentFile ?: currentBrowseDir
            renderActiveTab()
            return true
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        return true
    }

    private fun dp(v: Int): Int = MarketUiKit.dp(this, v)

    // -----------------------------------------------------------------------------------------
    // Root Shell: Atmospheric Liquid Glass Stage + Top Bar + Tab Container + Modal Scrim
    // -----------------------------------------------------------------------------------------
    private fun buildRootUi() {
        rootFrame = FrameLayout(this).apply { background = Theme.ambientBackground() }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        mainCol.addView(buildTopHeader())

        tabStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        mainCol.addView(tabStage)

        mainCol.addView(buildBottomHintBar())
        rootFrame.addView(mainCol)

        modalScrim = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#BA04070F"))
            isClickable = true
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(modalScrim)

        setContentView(rootFrame)
    }

    private fun buildTopHeader(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Left: Brand + Daemon Status Badge
        val leftCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
        val logoBox = FrameLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#D918263D"), Color.parseColor("#E60B1220"))
            ).apply {
                setStroke(dp(1), Theme.alpha(Theme.accent, 0.40f))
                cornerRadius = dp(11).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(9) }
        }
        logoBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_open)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        leftCluster.addView(logoBox)

        val brandTextCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "OPENSTATION "
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        })
        titleRow.addView(TextView(this).apply {
            text = "APPS"
            setTextColor(Theme.accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        })
        brandTextCol.addView(titleRow)

        tvDaemonPill = TextView(this).apply {
            text = "○ Проверка службы..."
            setTextColor(Theme.TEXT_MUTED)
            textSize = 10f
            maxLines = 1
        }
        brandTextCol.addView(tvDaemonPill)
        leftCluster.addView(brandTextCol)

        // Center: Flexible slot housing the Frosted 5-tab pill bar (guaranteed non-overlapping)
        val centerSlot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }
        val tabsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#9E0B1220"))
                setStroke(dp(1), Color.parseColor("#2EFFFFFF"))
                cornerRadius = dp(999).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val tabDefs = listOf(
            MarketTab.CATALOG to "Каталог",
            MarketTab.UPDATES to "Обновления",
            MarketTab.INSTALLED to "Установленные",
            MarketTab.LOCAL_APK to "APK с диска",
            MarketTab.SETTINGS to "Настройки"
        )
        tabDefs.forEachIndexed { idx, (tab, title) ->
            val tv = TextView(this).apply {
                text = title
                textSize = 11.5f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(11), dp(5), dp(11), dp(5))
                setBackgroundResource(R.drawable.bg_pill)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < tabDefs.lastIndex) marginEnd = dp(2)
                }
                MarketUiKit.attachFocusScale(this, 1.04f, 80L)
                setOnClickListener { switchTab(tab) }
            }
            tabButtons[tab] = tv
            tabsBox.addView(tv)
        }
        centerSlot.addView(tabsBox)

        // Right: Refresh + Back to Launcher
        val rightCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnRefreshTop = MarketUiKit.createPill(this, "↺ Обновить", PillStyle.GLASS) {
            Toast.makeText(this, "Обновляю каталог...", Toast.LENGTH_SHORT).show()
            probeDaemonAndLoadCatalog(forceNetwork = true)
        }.apply {
            textSize = 11.5f
            setPadding(dp(11), dp(5), dp(11), dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(7)
            }
        }
        val btnCloseTop = MarketUiKit.createPill(this, "← Домой", PillStyle.GLASS) {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }.apply {
            textSize = 11.5f
            setPadding(dp(11), dp(5), dp(11), dp(5))
        }
        rightCluster.addView(btnRefreshTop)
        rightCluster.addView(btnCloseTop)

        bar.addView(leftCluster)
        bar.addView(centerSlot)
        bar.addView(rightCluster)
        return bar
    }

    private fun buildBottomHintBar(): FrameLayout {
        val footer = FrameLayout(this).apply {
            setPadding(dp(26), dp(6), dp(26), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val hintText = TextView(this).apply {
            text = "OK — открыть  ·  ◀ ▶ — разделы  ·  ▲ ▼ — навигация  ·  Назад — закрыть"
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        tvCatalogSourceHint = TextView(this).apply {
            text = "OpenStation Launcher · Маркет v1.4"
            setTextColor(Theme.TEXT_FAINT)
            textSize = 11f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        }
        footer.addView(hintText)
        footer.addView(tvCatalogSourceHint)
        return footer
    }

    // -----------------------------------------------------------------------------------------
    // Data Loading & Live Updates
    // -----------------------------------------------------------------------------------------
    private fun probeDaemonAndLoadCatalog(forceNetwork: Boolean) {
        val customUrl = MarketPrefs.getCatalogUrl(this)
        thread(name = "MarketBootstrap") {
            val alive = MarketInstaller.checkDaemonOnline()
            val cat = MarketRepository.loadCatalog(applicationContext, customUrl, forceNetwork)
            val apps = MarketRepository.queryInstalledApps(applicationContext, showSystemInstalled)
            mainHandler.post {
                daemonRootAlive = alive
                catalog = cat
                installedApps = apps
                updateHeaderBadges()
                renderActiveTab()
                if (!catalogLoadedOnce) {
                    catalogLoadedOnce = true
                    focusPrimaryCatalogCardIfIdle()
                }
            }
        }
    }

    private fun refreshInstalledListAsync() {
        thread(name = "MarketInstalledScan") {
            val apps = MarketRepository.queryInstalledApps(applicationContext, showSystemInstalled)
            val alive = MarketInstaller.checkDaemonOnline()
            mainHandler.post {
                installedApps = apps
                daemonRootAlive = alive
                updateHeaderBadges()
                if (currentTab == MarketTab.CATALOG || currentTab == MarketTab.UPDATES || currentTab == MarketTab.INSTALLED) {
                    renderActiveTab()
                }
            }
        }
    }

    private fun updateHeaderBadges() {
        val preferRoot = MarketPrefs.isPreferDaemonRoot(this)
        if (preferRoot && daemonRootAlive) {
            tvDaemonPill.text = "● Root-установка (openstationd)"
            tvDaemonPill.setTextColor(Color.parseColor("#34D399"))
        } else if (daemonRootAlive) {
            tvDaemonPill.text = "● Служба активна · Системный режим"
            tvDaemonPill.setTextColor(Theme.accent)
        } else {
            tvDaemonPill.text = "○ Системный установщик Android"
            tvDaemonPill.setTextColor(Theme.accent)
        }

        val updates = computeAvailableUpdates()
        tabButtons[MarketTab.UPDATES]?.text = if (updates.isNotEmpty()) {
            "Обновления (${updates.size})"
        } else {
            "Обновления"
        }

        val cat = catalog
        tvCatalogSourceHint.text = when (cat?.source) {
            CatalogSource.NETWORK -> "Онлайн-каталог v${cat.catalogVersion} · ${cat.apps.size} прил."
            CatalogSource.CACHE -> "Кэш каталога · ${cat.apps.size} прил."
            else -> "Встроенный каталог · ${cat?.apps?.size ?: 0} прил."
        }
    }

    private fun computeAvailableUpdates(): List<MarketApp> {
        val cat = catalog ?: return emptyList()
        return cat.apps.filter { app ->
            val cur = MarketRepository.getInstalledVersionCode(packageManager, app.packageName)
            cur >= 0L && app.versionCode > cur
        }
    }

    private fun switchTab(tab: MarketTab) {
        currentTab = tab
        for ((t, tv) in tabButtons) {
            val active = (t == tab)
            tv.isSelected = active
            tv.setTextColor(if (active) Theme.accent else Theme.TEXT)
            tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        renderActiveTab()
    }

    private fun renderActiveTab() {
        tabStage.removeAllViews()
        val view = when (currentTab) {
            MarketTab.CATALOG -> buildCatalogTab()
            MarketTab.UPDATES -> buildUpdatesTab()
            MarketTab.INSTALLED -> buildInstalledTab()
            MarketTab.LOCAL_APK -> buildLocalApkTab()
            MarketTab.SETTINGS -> buildSettingsTab()
        }
        tabStage.addView(view)
    }

    private fun focusPrimaryCatalogCardIfIdle() {
        if (currentFocus == null) {
            tabButtons[currentTab]?.requestFocus()
        }
    }

    // -----------------------------------------------------------------------------------------
    // TAB 1: CATALOG (Category Filter Pills + 3-column Liquid Glass App Cards)
    // -----------------------------------------------------------------------------------------
    private fun buildCatalogTab(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(dp(26), dp(4), dp(26), dp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val cat = catalog
        if (cat == null) {
            col.addView(buildCenteredGlassCard("Загрузка каталога...", "Проверяем локальный кэш и список приложений.", null, null))
            scroll.addView(col)
            return scroll
        }

        val categories = LinkedHashSet<String>().apply {
            add("Все")
            for (app in cat.apps) add(app.category)
        }.toList()
        if (!categories.contains(selectedCategory)) {
            selectedCategory = "Все"
        }

        // Top Filter + Status Bar (scrollable category strip on the left so many categories never collide with right pill)
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val catScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            isFillViewport = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }
        val catPills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(8), dp(2))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        categories.forEachIndexed { idx, categoryName ->
            val selected = (categoryName == selectedCategory)
            val pill = TextView(this).apply {
                text = categoryName
                textSize = 11f
                isSelected = selected
                setTextColor(if (selected) Theme.accent else Theme.TEXT)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(11), dp(5), dp(11), dp(5))
                setBackgroundResource(R.drawable.bg_pill)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < categories.lastIndex) marginEnd = dp(6)
                }
                MarketUiKit.attachFocusScale(this, 1.05f, 80L)
                setOnClickListener {
                    selectedCategory = categoryName
                    renderActiveTab()
                }
            }
            catPills.addView(pill)
        }
        catScroll.addView(catPills)

        val rightActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val sourceLabel = when (cat.source) {
            CatalogSource.BUNDLED -> "Встроенный каталог · Настройки ⚙"
            CatalogSource.CACHE -> "Офлайн-кэш · Настройки"
            CatalogSource.NETWORK -> "Онлайн · ${cat.apps.size} прил."
        }
        val sourcePill = MarketUiKit.createPill(this, sourceLabel, PillStyle.GLASS) {
            switchTab(MarketTab.SETTINGS)
        }.apply {
            textSize = 11f
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        rightActionRow.addView(sourcePill)
        filterRow.addView(catScroll)
        filterRow.addView(rightActionRow)
        col.addView(filterRow)

        if (cat.warning.isNotEmpty()) {
            col.addView(buildSlimBanner(cat.warning))
        }

        val visibleApps = if (selectedCategory == "Все") cat.apps else cat.apps.filter { it.category == selectedCategory }
        if (visibleApps.isEmpty()) {
            col.addView(buildCenteredGlassCard("В этой категории пусто", "Выберите «Все» или добавьте свой каталог JSON в Настройках.", "Сбросить фильтр") {
                selectedCategory = "Все"
                renderActiveTab()
            })
            scroll.addView(col)
            return scroll
        }

        val grid = GridLayout(this).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val gap = dp(7)
        val availW = resources.displayMetrics.widthPixels - dp(52)
        val colW = maxOf(dp(220), (availW / 3) - gap * 2)

        for (app in visibleApps) {
            val card = buildCatalogAppCard(app, colW, gap)
            grid.addView(card)
        }
        col.addView(grid)
        scroll.addView(col)
        return scroll
    }

    private fun buildCatalogAppCard(app: MarketApp, colW: Int, marginPx: Int): LinearLayout {
        val installedCode = MarketRepository.getInstalledVersionCode(packageManager, app.packageName)
        val installedName = MarketRepository.getInstalledVersionName(packageManager, app.packageName)
        val hasUpdate = installedCode >= 0L && app.versionCode > installedCode
        val isInstalled = installedCode >= 0L

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_tile)
            isFocusable = true
            isClickable = true
            layoutParams = GridLayout.LayoutParams().apply {
                width = colW
                height = dp(146)
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            MarketUiKit.attachFocusScale(this, 1.03f, 95L)
            setOnClickListener { openAppDetailModal(app, this) }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val accent = app.accentColorOrDefault()
        val ivIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(12) }
        }
        val monoFallback = MarketImageLoader.createMonogramDrawable(app.name, accent, resources.displayMetrics.density)
        val installedIcon = if (isInstalled) {
            try {
                packageManager.getApplicationIcon(app.packageName)
            } catch (_: Throwable) {
                null
            }
        } else null
        if (installedIcon != null && app.iconUrl.isEmpty()) {
            ivIcon.setImageDrawable(installedIcon)
        } else {
            MarketImageLoader.loadIcon(this, ivIcon, app.iconUrl, installedIcon ?: monoFallback)
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = app.name
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        titleCol.addView(TextView(this).apply {
            text = "v${app.versionName} · ${MarketRepository.formatBytes(app.sizeBytes)}"
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })

        val statusBadge = when {
            hasUpdate -> MarketUiKit.createBadgeChip(this, "Обновить", "#0A0A0F", Theme.hex(1f), "#FFFFFF")
            isInstalled -> MarketUiKit.createBadgeChip(this, "Установлено", "#34D399", "#28059669", "#6634D399")
            else -> MarketUiKit.createBadgeChip(this, "Доступно", Theme.hex(1f), Theme.hex(0.15f), Theme.hex(0.4f))
        }

        topRow.addView(ivIcon)
        topRow.addView(titleCol)
        topRow.addView(statusBadge)

        val desc = TextView(this).apply {
            text = app.description.ifEmpty { app.packageName }
            setTextColor(Theme.TEXT)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            }
        }

        val bottomMeta = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        bottomMeta.addView(TextView(this).apply {
            text = app.category
            setTextColor(Theme.TEXT_FAINT)
            textSize = 10.5f
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
        })
        bottomMeta.addView(TextView(this).apply {
            text = if (isInstalled) "На станции: v$installedName" else app.packageName
            setTextColor(if (hasUpdate) Theme.accent else Theme.TEXT_FAINT)
            textSize = 10.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
        })

        card.addView(topRow)
        card.addView(desc)
        card.addView(bottomMeta)
        return card
    }

    // -----------------------------------------------------------------------------------------
    // TAB 2: UPDATES
    // -----------------------------------------------------------------------------------------
    private fun buildUpdatesTab(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(dp(26), dp(6), dp(26), dp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val updates = computeAvailableUpdates()
        if (updates.isEmpty()) {
            col.addView(buildCenteredGlassCard(
                "Все приложения обновлены ✓",
                "Установленные на Яндекс Станции Макс приложения соответствуют последним версиям в каталоге.",
                "Проверить каталог"
            ) {
                probeDaemonAndLoadCatalog(forceNetwork = true)
            })
            scroll.addView(col)
            return scroll
        }

        for (app in updates) {
            val curName = MarketRepository.getInstalledVersionName(packageManager, app.packageName)
            val row = makeGlassListRow(
                icon = safePackageIcon(app.packageName) ?: MarketImageLoader.createMonogramDrawable(app.name, app.accentColorOrDefault(), resources.displayMetrics.density),
                title = app.name,
                subtitle = "${app.packageName} · v$curName → v${app.versionName} (${MarketRepository.formatBytes(app.sizeBytes)})",
                detail = app.changelog.ifEmpty { app.description },
                badgeText = "Обновление",
                badgeColors = Triple("#0A0A0F", Theme.hex(1f), "#FFFFFF"),
                primaryLabel = "Обновить",
                primaryStyle = PillStyle.GOLD,
                onPrimary = { startCatalogInstall(app, rowAnchor = it) },
                secondaryLabel = "Подробнее",
                onSecondary = { openAppDetailModal(app, it) }
            )
            col.addView(row)
        }
        scroll.addView(col)
        return scroll
    }

    // -----------------------------------------------------------------------------------------
    // TAB 3: INSTALLED APPS
    // -----------------------------------------------------------------------------------------
    private fun buildInstalledTab(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(dp(26), dp(4), dp(26), dp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val toggleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val btnUser = makeFilterTogglePill("Сторонние приложения", !showSystemInstalled) {
            showSystemInstalled = false
            refreshInstalledListAsync()
        }
        val btnAll = makeFilterTogglePill("Все (+системные)", showSystemInstalled) {
            showSystemInstalled = true
            refreshInstalledListAsync()
        }
        toggleBar.addView(btnUser)
        toggleBar.addView(btnAll)
        toggleBar.addView(TextView(this).apply {
            text = "Найдено: ${installedApps.size}"
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10)
            }
        })
        col.addView(toggleBar)

        if (installedApps.isEmpty()) {
            col.addView(buildCenteredGlassCard(
                "Сторонних приложений не найдено",
                "Установите нужное приложение из Каталога или выберите APK-файл с диска.",
                "Открыть Каталог"
            ) { switchTab(MarketTab.CATALOG) })
            scroll.addView(col)
            return scroll
        }

        for (entry in installedApps) {
            val verLabel = if (entry.versionName.isNotEmpty()) "v${entry.versionName} (${entry.versionCode})" else "build ${entry.versionCode}"
            val badge = when {
                entry.isProtected -> Triple("#ADADB1", "#331E293B", "#44FFFFFF")
                entry.isTvApp -> Triple(Theme.hex(1f), Theme.hex(0.15f), Theme.hex(0.4f))
                else -> Triple("#C084FC", "#28581C87", "#66A855F7")
            }
            val badgeTxt = when {
                entry.isProtected -> "Система"
                entry.isTvApp -> "Android TV"
                else -> "Приложение"
            }

            val canLaunch = entry.launchIntent != null && entry.packageName != packageName
            val canDelete = !entry.isProtected
            val row = makeGlassListRow(
                icon = entry.icon ?: MarketImageLoader.createMonogramDrawable(entry.label, Theme.accent, resources.displayMetrics.density),
                title = entry.label,
                subtitle = "${entry.packageName} · $verLabel",
                detail = "",
                badgeText = badgeTxt,
                badgeColors = badge,
                primaryLabel = if (canLaunch) "Открыть" else null,
                primaryStyle = PillStyle.CYAN,
                onPrimary = {
                    val li = entry.launchIntent
                    if (li != null) {
                        try {
                            startActivity(li)
                        } catch (e: Throwable) {
                            Toast.makeText(this, "Не удалось открыть: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                secondaryLabel = if (canDelete) "Удалить" else null,
                secondaryStyle = PillStyle.DANGER,
                onSecondary = { anchor -> confirmUninstallModal(entry, anchor) }
            )
            col.addView(row)
        }
        scroll.addView(col)
        return scroll
    }

    // -----------------------------------------------------------------------------------------
    // TAB 4: LOCAL APK FILE BROWSER (Built-in replacement for missing Android TV DocumentsUI)
    // -----------------------------------------------------------------------------------------
    private fun buildLocalApkTab(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(dp(26), dp(4), dp(26), dp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (!hasStorageReadPermission()) {
            col.addView(buildCenteredGlassCard(
                "Требуется разрешение на чтение файлов",
                "Чтобы показать загруженные APK из папки Download и накопителя, разрешите доступ к памяти.",
                "Разрешить доступ"
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE_PERM)
            }.apply {
                (this as? LinearLayout)?.addView(
                    MarketUiKit.createPill(this@MarketActivity, "Настройки разрешений Android", PillStyle.GLASS) {
                        openAppPermissionSettings()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(10)
                        }
                    }
                )
            })
            scroll.addView(col)
            return scroll
        }

        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val sdRoot = Environment.getExternalStorageDirectory()
        if (!currentBrowseDir.exists() || !currentBrowseDir.isDirectory) {
            currentBrowseDir = resolveDefaultApkDir()
        }

        // Navigation Header Strip
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val btnUp = MarketUiKit.createPill(this, "↑ Вверх", PillStyle.GLASS) {
            if (canNavigateUpDir()) {
                currentBrowseDir = currentBrowseDir.parentFile ?: currentBrowseDir
                renderActiveTab()
            }
        }.apply {
            alpha = if (canNavigateUpDir()) 1f else 0.45f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
        val btnDl = MarketUiKit.createPill(this, "Загрузки", PillStyle.GLASS) {
            currentBrowseDir = if (dlDir.isDirectory) dlDir else sdRoot
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        }
        val btnSd = MarketUiKit.createPill(this, "Память", PillStyle.GLASS) {
            currentBrowseDir = sdRoot
            renderActiveTab()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
        }
        val tvPath = TextView(this).apply {
            text = currentBrowseDir.absolutePath
            setTextColor(Theme.accent)
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        navBar.addView(btnUp)
        navBar.addView(btnDl)
        navBar.addView(btnSd)
        navBar.addView(tvPath)
        col.addView(navBar)

        val entries = currentBrowseDir.listFiles()
        val dirs = ArrayList<File>()
        val apks = ArrayList<File>()
        if (entries != null) {
            for (f in entries) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) dirs.add(f)
                else if (f.isFile && f.name.lowercase(Locale.US).endsWith(".apk")) apks.add(f)
            }
        }
        dirs.sortBy { it.name.lowercase(Locale.getDefault()) }
        apks.sortBy { it.name.lowercase(Locale.getDefault()) }

        if (dirs.isEmpty() && apks.isEmpty()) {
            col.addView(buildCenteredGlassCard(
                "APK-файлы не найдены",
                "Скопируйте .apk в ${currentBrowseDir.absolutePath} (через веб-пульт или USB) и нажмите «Обновить».",
                "Перейти в Загрузки"
            ) {
                currentBrowseDir = if (dlDir.isDirectory) dlDir else sdRoot
                renderActiveTab()
            })
            scroll.addView(col)
            return scroll
        }

        for (dir in dirs) {
            val subCount = dir.listFiles()?.count { it.isFile && it.name.lowercase(Locale.US).endsWith(".apk") } ?: 0
            val subInfo = if (subCount > 0) "Папка · $subCount APK" else "Папка"
            val row = makeGlassListRow(
                icon = MarketImageLoader.createMonogramDrawable("📁", Theme.accent, resources.displayMetrics.density),
                title = dir.name,
                subtitle = subInfo,
                detail = "",
                badgeText = "Каталог",
                badgeColors = Triple(Theme.hex(1f), Theme.hex(0.15f), Theme.hex(0.33f)),
                primaryLabel = "Открыть",
                primaryStyle = PillStyle.GLASS,
                onPrimary = {
                    currentBrowseDir = dir
                    renderActiveTab()
                },
                secondaryLabel = null,
                onSecondary = {}
            )
            col.addView(row)
        }

        for (apk in apks) {
            val rowHolder = makeGlassListRowWithRefs(
                icon = MarketImageLoader.createMonogramDrawable("A", Theme.accent, resources.displayMetrics.density),
                title = apk.name,
                subtitle = MarketRepository.formatBytes(apk.length()),
                detail = "",
                badgeText = "APK",
                badgeColors = Triple(Theme.hex(1f), Theme.hex(0.15f), Theme.hex(0.4f)),
                primaryLabel = "Установить",
                primaryStyle = PillStyle.GOLD,
                onPrimary = { anchor -> inspectAndConfirmLocalApk(apk, anchor) },
                secondaryLabel = null,
                onSecondary = {}
            )
            col.addView(rowHolder.root)

            inspectExecutor.execute {
                val info = MarketRepository.inspectLocalApk(applicationContext, apk)
                if (info != null) {
                    mainHandler.post {
                        if (!rowHolder.root.isAttachedToWindow) return@post
                        info.icon?.let { rowHolder.ivIcon.setImageDrawable(it) }
                        rowHolder.tvTitle.text = info.label
                        rowHolder.tvSub.text = "${info.packageName} · v${info.versionName} (${info.versionCode}) · ${MarketRepository.formatBytes(info.sizeBytes)}"
                    }
                }
            }
        }

        scroll.addView(col)
        return scroll
    }

    // -----------------------------------------------------------------------------------------
    // TAB 5: REDESIGNED COMPACT SETTINGS (Two balanced Liquid Glass columns, zero button clipping)
    // -----------------------------------------------------------------------------------------
    private fun buildSettingsTab(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(dp(26), dp(4), dp(26), dp(8))
        }
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // LEFT CARD: Catalog URL & Install Preferences
        val leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                marginEnd = dp(14)
            }
        }
        leftCard.addView(TextView(this).apply {
            text = "Источник каталога и установка"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        leftCard.addView(TextView(this).apply {
            text = "URL каталога (JSON по HTTPS/HTTP). Оставьте пустым для встроенного каталога."
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11.5f
            setPadding(0, dp(4), 0, dp(10))
        })

        val editUrl = EditText(this).apply {
            setText(MarketPrefs.getCatalogUrl(this@MarketActivity))
            hint = MarketPrefs.DEFAULT_CATALOG_URL
            setHintTextColor(Theme.TEXT_FAINT)
            setTextColor(Color.WHITE)
            textSize = 13.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9080F1E"))
                setStroke(dp(1), Theme.alpha(Theme.accent, 0.27f))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        leftCard.addView(editUrl)

        var autoCheck = MarketPrefs.getAutoCheckUpdates(this)
        var preferRoot = MarketPrefs.isPreferDaemonRoot(this)

        lateinit var pillAuto: TextView
        pillAuto = makeSettingToggleRow("Автопроверка обновлений при запуске", autoCheck) {
            autoCheck = !autoCheck
            MarketPrefs.setAutoCheckUpdates(this, autoCheck)
            updateSettingToggleText(pillAuto, "Автопроверка обновлений при запуске", autoCheck)
        }
        leftCard.addView(pillAuto)

        lateinit var pillRoot: TextView
        pillRoot = makeSettingToggleRow("Тихая root-установка через openstationd", preferRoot) {
            preferRoot = !preferRoot
            MarketPrefs.setPreferDaemonRoot(this, preferRoot)
            updateSettingToggleText(pillRoot, "Тихая root-установка через openstationd", preferRoot)
            updateHeaderBadges()
        }
        leftCard.addView(pillRoot)

        leftCard.addView(TextView(this).apply {
            text = if (daemonRootAlive) {
                "● Служба openstationd отвечает на 127.0.0.1:8080 — APK ставятся без диалогов."
            } else {
                "○ openstationd не отвечает — используется системный PackageInstaller."
            }
            setTextColor(if (daemonRootAlive) Color.parseColor("#34D399") else Theme.accent)
            textSize = 11f
            setPadding(dp(4), 0, 0, dp(12))
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnSave = MarketUiKit.createPill(this, "Сохранить и обновить", PillStyle.GOLD) {
            val u = editUrl.text.toString().trim()
            if (u.isNotEmpty() && !u.startsWith("https://") && !u.startsWith("http://")) {
                Toast.makeText(this, "Укажите корректный http:// или https:// URL", Toast.LENGTH_LONG).show()
                return@createPill
            }
            MarketPrefs.setCatalogUrl(this, u)
            Toast.makeText(this, "Настройки сохранены, обновляю каталог", Toast.LENGTH_SHORT).show()
            probeDaemonAndLoadCatalog(forceNetwork = true)
            switchTab(MarketTab.CATALOG)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        val btnReset = MarketUiKit.createPill(this, "Основной репозиторий", PillStyle.GLASS) {
            editUrl.setText(MarketPrefs.DEFAULT_CATALOG_URL)
            MarketPrefs.setCatalogUrl(this, MarketPrefs.DEFAULT_CATALOG_URL)
            Toast.makeText(this, "Восстановлен каталог OpenStation Market", Toast.LENGTH_SHORT).show()
            probeDaemonAndLoadCatalog(forceNetwork = true)
        }
        actionRow.addView(btnSave)
        actionRow.addView(btnReset)
        leftCard.addView(actionRow)

        // RIGHT CARD: Station Info, Permissions & Security
        val rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f)
        }
        rightCard.addView(TextView(this).apply {
            text = "Станция и безопасность"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })

        val availGb = try {
            val avail = StatFs(filesDir.absolutePath).availableBytes
            MarketRepository.formatBytes(avail)
        } catch (_: Throwable) {
            "—"
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val canUnknown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { packageManager.canRequestPackageInstalls() } catch (_: Throwable) { false }
        } else true
        val hasStorage = hasStorageReadPermission()

        val sysBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = MarketUiKit.makeGlassSurface(this@MarketActivity, "#990A101E", "#24FFFFFF", 14)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
        }
        sysBox.addView(TextView(this).apply {
            text = "Устройство: ${Build.MODEL} ($abi)\nAndroid ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\nСвободно в /data: $availGb"
            setTextColor(Theme.TEXT)
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        rightCard.addView(sysBox)

        val unknownLabel = if (canUnknown) "✓ Установка из неизвестных источников разрешена" else "Разрешить установку из неизвестных источников"
        rightCard.addView(MarketUiKit.createPill(this, unknownLabel, PillStyle.GLASS) {
            openUnknownSourcesSettings()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        })

        val storageLabel = if (hasStorage) "✓ Доступ к файлам (/sdcard/Download) выдан" else "Выдать доступ к памяти (APK с диска)"
        rightCard.addView(MarketUiKit.createPill(this, storageLabel, PillStyle.GLASS) {
            if (!hasStorage) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE_PERM)
            } else {
                switchTab(MarketTab.LOCAL_APK)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        })

        rightCard.addView(TextView(this).apply {
            text = "OpenStation Market v1.4 — встроенный модуль лаунчера.\nКаждый APK перед установкой проходит проверку SHA-256, minSdk и совпадения цифровой подписи."
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        columns.addView(leftCard)
        columns.addView(rightCard)
        scroll.addView(columns)
        return scroll
    }

    // -----------------------------------------------------------------------------------------
    // MODALS (Frosted Liquid Glass Overlays — no ugly gray TV AlertDialogs)
    // -----------------------------------------------------------------------------------------
    private fun showModal(content: View, anchorView: View?, widthDp: Int = 620) {
        lastModalAnchor = anchorView
        modalScrim.removeAllViews()
        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            setPadding(dp(24), dp(22), dp(24), dp(22))
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                dp(widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        card.addView(content)
        modalScrim.addView(card)
        modalScrim.visibility = View.VISIBLE
        modalScrim.alpha = 0f
        modalScrim.animate().alpha(1f).setDuration(140L).start()
    }

    private fun closeModal() {
        modalScrim.visibility = View.GONE
        modalScrim.removeAllViews()
        lastModalAnchor?.requestFocus()
        lastModalAnchor = null
    }

    private fun openAppDetailModal(app: MarketApp, anchor: View) {
        val installedVer = MarketRepository.getInstalledVersionCode(packageManager, app.packageName)
        val installedName = MarketRepository.getInstalledVersionName(packageManager, app.packageName)
        val isInstalled = installedVer >= 0L
        val hasUpdate = isInstalled && app.versionCode > installedVer
        val isProtected = MarketRepository.PROTECTED_PACKAGES.contains(app.packageName)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(16) }
        }
        val mono = MarketImageLoader.createMonogramDrawable(app.name, app.accentColorOrDefault(), resources.displayMetrics.density)
        MarketImageLoader.loadIcon(this, iv, app.iconUrl, safePackageIcon(app.packageName) ?: mono)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = app.name
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "${app.packageName} · ${app.category}"
            setTextColor(Theme.TEXT_MUTED)
            textSize = 12f
        })
        head.addView(iv)
        head.addView(titleCol)
        root.addView(head)

        root.addView(MarketUiKit.makeHorizontalDivider(this))

        // Specs box
        val verSummary = when {
            hasUpdate -> "Установлено v$installedName → Доступно v${app.versionName}"
            isInstalled -> "Установлено v$installedName (код $installedVer)"
            else -> "Версия v${app.versionName} (код ${app.versionCode})"
        }
        val shortSha = app.sha256.take(12) + "…" + app.sha256.takeLast(8)
        root.addView(TextView(this).apply {
            text = "$verSummary  ·  Размер: ${MarketRepository.formatBytes(app.sizeBytes)}  ·  Android SDK ${app.minSdk}+\n🔒 SHA-256 проверен: $shortSha"
            setTextColor(Theme.accent)
            textSize = 11.5f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = MarketUiKit.makeGlassSurface(this@MarketActivity, "#800C0C12", Theme.hex(0.15f), 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        })

        if (app.description.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = app.description
                setTextColor(Theme.TEXT)
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            })
        }
        if (app.changelog.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Что нового: ${app.changelog}"
                setTextColor(Theme.accent)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                }
            })
        }

        // Actions
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val btnClose = MarketUiKit.createPill(this, "Закрыть", PillStyle.GLASS) { closeModal() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        btnRow.addView(btnClose)

        if (isInstalled && !isProtected) {
            btnRow.addView(MarketUiKit.createPill(this, "Удалить", PillStyle.DANGER) {
                MarketInstaller.uninstallPackage(this, app.packageName, MarketPrefs.isPreferDaemonRoot(this)) { ok, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (ok) {
                        closeModal()
                        refreshInstalledListAsync()
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(10)
                }
            })
        }

        if (isInstalled && app.packageName != packageName) {
            val li = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (li != null) {
                btnRow.addView(MarketUiKit.createPill(this, "Открыть", PillStyle.CYAN) {
                    closeModal()
                    startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(10)
                    }
                })
            }
        }

        val primaryText = when {
            hasUpdate -> "Обновить"
            isInstalled -> "Переустановить"
            else -> "Установить"
        }
        val primaryBtn = MarketUiKit.createPill(this, primaryText, PillStyle.GOLD) {
            startCatalogInstall(app, anchor)
        }
        btnRow.addView(primaryBtn)
        root.addView(btnRow)

        showModal(root, anchor, 640)
        primaryBtn.requestFocus()
    }

    private fun inspectAndConfirmLocalApk(apkFile: File, anchor: View) {
        val info = MarketRepository.inspectLocalApk(this, apkFile)
        if (info == null) {
            Toast.makeText(this, "Некорректный или повреждённый APK-файл", Toast.LENGTH_LONG).show()
            return
        }
        val curCode = MarketRepository.getInstalledVersionCode(packageManager, info.packageName)
        val curName = MarketRepository.getInstalledVersionName(packageManager, info.packageName)
        val installedSigs = if (curCode >= 0L) MarketRepository.getInstalledSignatureDigests(packageManager, info.packageName) else emptySet()
        val sigMismatch = curCode >= 0L && !MarketRepository.doSignaturesOverlap(installedSigs, info.signatures)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            setImageDrawable(info.icon ?: MarketImageLoader.createMonogramDrawable(info.label, Theme.accent, resources.displayMetrics.density))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = info.label
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "${info.packageName} · v${info.versionName} (${info.versionCode})"
            setTextColor(Theme.TEXT_MUTED)
            textSize = 12f
        })
        head.addView(titleCol)
        root.addView(head)
        root.addView(MarketUiKit.makeHorizontalDivider(this))

        val statusTxt = when {
            curCode < 0L -> "Новое приложение · ${MarketRepository.formatBytes(info.sizeBytes)} · SDK ${info.minSdk}+"
            info.versionCode > curCode -> "Обновление v$curName → v${info.versionName} · ${MarketRepository.formatBytes(info.sizeBytes)}"
            info.versionCode == curCode -> "Переустановка текущей версии v$curName"
            else -> "Даунгрейд v$curName → v${info.versionName} (поддерживается root-режимом)"
        }
        root.addView(TextView(this).apply {
            text = "$statusTxt\nФайл: ${apkFile.name}\nПримечание: для локальных файлов SHA-256 не сверяется с каталогом."
            setTextColor(Theme.TEXT)
            textSize = 12.5f
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        })

        if (sigMismatch) {
            root.addView(buildSlimBanner("Подпись этого APK отличается от установленной версии! Установка без удаления старой невозможна."))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnCancel = MarketUiKit.createPill(this, "Отмена", PillStyle.GLASS) { closeModal() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        val btnInstall = MarketUiKit.createPill(this, "Установить APK", PillStyle.GOLD) {
            startLocalApkInstall(apkFile, info.label, anchor)
        }
        btnRow.addView(btnCancel)
        btnRow.addView(btnInstall)
        root.addView(btnRow)

        showModal(root, anchor, 580)
        if (!sigMismatch) btnInstall.requestFocus() else btnCancel.requestFocus()
    }

    private fun confirmUninstallModal(entry: InstalledAppEntry, anchor: View) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Удалить ${entry.label}?"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Пакет: ${entry.packageName}\nВсе данные приложения на станции будут удалены."
            setTextColor(Theme.TEXT_MUTED)
            textSize = 12.5f
            setPadding(0, dp(8), 0, dp(16))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnCancel = MarketUiKit.createPill(this, "Отмена", PillStyle.GLASS) { closeModal() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            }
        }
        val btnDel = MarketUiKit.createPill(this, "Удалить", PillStyle.DANGER) {
            closeModal()
            MarketInstaller.uninstallPackage(this, entry.packageName, MarketPrefs.isPreferDaemonRoot(this)) { ok, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) refreshInstalledListAsync()
            }
        }
        row.addView(btnCancel)
        row.addView(btnDel)
        root.addView(row)
        showModal(root, anchor, 500)
        btnCancel.requestFocus()
    }

    private fun startCatalogInstall(app: MarketApp, rowAnchor: View?) {
        val cancelFlag = AtomicBoolean(false)
        activeTaskCancel = cancelFlag
        activeTaskRunning = true

        val (uiRoot, tvTitle, tvStage, pb, tvStats, btnAction) = buildProgressModalViews(app.name, cancelFlag)
        showModal(uiRoot, rowAnchor, 540)
        btnAction.requestFocus()

        MarketInstaller.installFromCatalog(
            context = this,
            app = app,
            preferDaemonRoot = MarketPrefs.isPreferDaemonRoot(this),
            cancelled = cancelFlag,
            listener = object : MarketInstaller.TaskListener {
                override fun onStage(event: InstallStageEvent) {
                    when (event.stage) {
                        "download" -> {
                            tvStage.text = "Шаг 1/3 · Загрузка APK..."
                            pb.isIndeterminate = event.totalBytes <= 0L
                            if (!pb.isIndeterminate) pb.progress = event.progressPct
                            val speed = if (event.bytesPerSec > 0L) " · ${MarketRepository.formatBytes(event.bytesPerSec)}/с" else ""
                            tvStats.text = "${MarketRepository.formatBytes(event.downloadedBytes)} / ${MarketRepository.formatBytes(event.totalBytes)}$speed"
                        }
                        "verify" -> {
                            tvStage.text = "Шаг 2/3 · Проверка SHA-256 и подписи..."
                            pb.isIndeterminate = true
                            tvStats.text = "Проверка целостности файла..."
                        }
                        "install" -> {
                            tvStage.text = if (daemonRootAlive && MarketPrefs.isPreferDaemonRoot(this@MarketActivity)) {
                                "Шаг 3/3 · Установка на станцию (root)..."
                            } else {
                                "Шаг 3/3 · Запуск системного установщика..."
                            }
                            pb.isIndeterminate = true
                            tvStats.text = "Пожалуйста, подождите..."
                        }
                    }
                }

                override fun onSuccess(message: String) {
                    activeTaskRunning = false
                    pb.isIndeterminate = false
                    pb.progress = 100
                    tvTitle.text = "${app.name} — Готово"
                    tvStage.text = "✓ $message"
                    tvStage.setTextColor(Color.parseColor("#34D399"))
                    tvStats.text = "Версия v${app.versionName} установлена"
                    btnAction.text = "Закрыть"
                    refreshInstalledListAsync()
                }

                override fun onError(message: String, needsUnknownSourcesPermission: Boolean) {
                    activeTaskRunning = false
                    pb.isIndeterminate = false
                    tvTitle.text = "${app.name} — Ошибка"
                    tvStage.text = "✕ $message"
                    tvStage.setTextColor(Theme.DANGER_FG)
                    tvStats.text = if (needsUnknownSourcesPermission) {
                        "Разрешите установку из неизвестных источников или включите openstationd."
                    } else {
                        "Установка не выполнена."
                    }
                    btnAction.text = if (needsUnknownSourcesPermission) "Открыть настройки" else "Закрыть"
                    if (needsUnknownSourcesPermission) {
                        btnAction.setOnClickListener {
                            closeModal()
                            openUnknownSourcesSettings()
                        }
                    }
                }
            }
        )
    }

    private fun startLocalApkInstall(apkFile: File, label: String, anchor: View?) {
        val cancelFlag = AtomicBoolean(false)
        activeTaskCancel = cancelFlag
        activeTaskRunning = true

        val (uiRoot, tvTitle, tvStage, pb, tvStats, btnAction) = buildProgressModalViews(label, cancelFlag)
        showModal(uiRoot, anchor, 540)
        btnAction.requestFocus()

        MarketInstaller.installLocalApk(
            context = this,
            apkFile = apkFile,
            preferDaemonRoot = MarketPrefs.isPreferDaemonRoot(this),
            listener = object : MarketInstaller.TaskListener {
                override fun onStage(event: InstallStageEvent) {
                    tvStage.text = if (daemonRootAlive && MarketPrefs.isPreferDaemonRoot(this@MarketActivity)) {
                        "Установка APK на станцию (root)..."
                    } else {
                        "Подготовка системного установщика..."
                    }
                    pb.isIndeterminate = true
                    tvStats.text = apkFile.name
                }

                override fun onSuccess(message: String) {
                    activeTaskRunning = false
                    pb.isIndeterminate = false
                    pb.progress = 100
                    tvTitle.text = "$label — Готово"
                    tvStage.text = "✓ $message"
                    tvStage.setTextColor(Color.parseColor("#34D399"))
                    btnAction.text = "Закрыть"
                    refreshInstalledListAsync()
                }

                override fun onError(message: String, needsUnknownSourcesPermission: Boolean) {
                    activeTaskRunning = false
                    pb.isIndeterminate = false
                    tvTitle.text = "$label — Ошибка"
                    tvStage.text = "✕ $message"
                    tvStage.setTextColor(Theme.DANGER_FG)
                    btnAction.text = if (needsUnknownSourcesPermission) "Открыть настройки" else "Закрыть"
                    if (needsUnknownSourcesPermission) {
                        btnAction.setOnClickListener {
                            closeModal()
                            openUnknownSourcesSettings()
                        }
                    }
                }
            }
        )
    }

    private data class ProgressModalViews(
        val root: LinearLayout,
        val tvTitle: TextView,
        val tvStage: TextView,
        val pb: ProgressBar,
        val tvStats: TextView,
        val btnAction: TextView
    )

    private fun buildProgressModalViews(appName: String, cancelFlag: AtomicBoolean): ProgressModalViews {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tvTitle = TextView(this).apply {
            text = appName
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvStage = TextView(this).apply {
            text = "Подготовка..."
            setTextColor(Theme.accent)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = getDrawable(R.drawable.bg_progress_gold)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
                bottomMargin = dp(10)
            }
        }
        val tvStats = TextView(this).apply {
            text = ""
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11.5f
            setPadding(0, 0, 0, dp(16))
        }
        val btnAction = MarketUiKit.createPill(this, "Отменить", PillStyle.GLASS) {
            if (activeTaskRunning) {
                cancelFlag.set(true)
            } else {
                closeModal()
            }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(btnAction)
        }
        root.addView(tvTitle)
        root.addView(tvStage)
        root.addView(pb)
        root.addView(tvStats)
        root.addView(btnRow)
        return ProgressModalViews(root, tvTitle, tvStage, pb, tvStats, btnAction)
    }

    // -----------------------------------------------------------------------------------------
    // UI Helpers & Reusable Glass Row Builders
    // -----------------------------------------------------------------------------------------
    private data class GlassRowRefs(
        val root: LinearLayout,
        val ivIcon: ImageView,
        val tvTitle: TextView,
        val tvSub: TextView
    )

    private fun makeGlassListRow(
        icon: Drawable,
        title: String,
        subtitle: String,
        detail: String,
        badgeText: String,
        badgeColors: Triple<String, String, String>,
        primaryLabel: String?,
        primaryStyle: PillStyle = PillStyle.GOLD,
        onPrimary: (View) -> Unit,
        secondaryLabel: String?,
        secondaryStyle: PillStyle = PillStyle.GLASS,
        onSecondary: (View) -> Unit
    ): LinearLayout {
        return makeGlassListRowWithRefs(
            icon, title, subtitle, detail, badgeText, badgeColors,
            primaryLabel, primaryStyle, onPrimary, secondaryLabel, secondaryStyle, onSecondary
        ).root
    }

    private fun makeGlassListRowWithRefs(
        icon: Drawable,
        title: String,
        subtitle: String,
        detail: String,
        badgeText: String,
        badgeColors: Triple<String, String, String>,
        primaryLabel: String?,
        primaryStyle: PillStyle = PillStyle.GOLD,
        onPrimary: (View) -> Unit,
        secondaryLabel: String?,
        secondaryStyle: PillStyle = PillStyle.GLASS,
        onSecondary: (View) -> Unit
    ): GlassRowRefs {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = MarketUiKit.makeGlassSurface(this@MarketActivity, "#B5101A2C", "#24FFFFFF", 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        val iv = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
        }
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            setTextColor(Theme.TEXT_MUTED)
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(tvTitle)
        textCol.addView(tvSub)
        if (detail.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = detail
                setTextColor(Theme.TEXT)
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }

        val badge = MarketUiKit.createBadgeChip(this, badgeText, badgeColors.first, badgeColors.second, badgeColors.third).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
        }

        row.addView(iv)
        row.addView(textCol)
        row.addView(badge)

        if (secondaryLabel != null) {
            val secBtn = MarketUiKit.createPill(this, secondaryLabel, secondaryStyle) { }
            secBtn.setOnClickListener { onSecondary(secBtn) }
            secBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (primaryLabel != null) marginEnd = dp(8)
            }
            row.addView(secBtn)
        }
        if (primaryLabel != null) {
            val priBtn = MarketUiKit.createPill(this, primaryLabel, primaryStyle) { }
            priBtn.setOnClickListener { onPrimary(priBtn) }
            row.addView(priBtn)
        }

        return GlassRowRefs(row, iv, tvTitle, tvSub)
    }

    private fun buildCenteredGlassCard(title: String, body: String, ctaLabel: String?, onCta: (() -> Unit)?): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundResource(R.drawable.bg_card_bork_hero)
            layoutParams = FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(40)
            }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = body
            setTextColor(Theme.TEXT_MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        if (ctaLabel != null && onCta != null) {
            card.addView(MarketUiKit.createPill(this, ctaLabel, PillStyle.GOLD) { onCta() })
        }
        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(card)
        }
        return wrapper
    }

    private fun buildSlimBanner(msg: String): View {
        return TextView(this).apply {
            text = "⚠ $msg"
            setTextColor(Theme.accent)
            textSize = 11.5f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = MarketUiKit.makeGlassSurface(this@MarketActivity, "#4D78350F", "#77F59E0B", 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun makeFilterTogglePill(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            isSelected = selected
            setTextColor(if (selected) Theme.accent else Theme.TEXT)
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setBackgroundResource(R.drawable.bg_pill)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            MarketUiKit.attachFocusScale(this, 1.04f, 80L)
            setOnClickListener { onClick() }
        }
    }

    private fun makeSettingToggleRow(label: String, enabled: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            textSize = 12.5f
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = MarketUiKit.makeFocusableGlassSelector(this@MarketActivity, radiusDp = 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            MarketUiKit.attachFocusScale(this, 1.02f, 80L)
            updateSettingToggleText(this, label, enabled)
            setOnClickListener { onClick() }
        }
    }

    private fun updateSettingToggleText(tv: TextView, label: String, enabled: Boolean) {
        tv.text = if (enabled) "✓ $label: ВКЛ" else "○ $label: ВЫКЛ"
        tv.setTextColor(if (enabled) Color.parseColor("#34D399") else Theme.TEXT)
    }

    // -----------------------------------------------------------------------------------------
    // System / Permission / Package Helpers
    // -----------------------------------------------------------------------------------------
    private fun safePackageIcon(pkg: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasStorageReadPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveDefaultApkDir(): File {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dl.isDirectory) return dl
        val sd = Environment.getExternalStorageDirectory()
        if (sd.isDirectory) return sd
        return File("/sdcard")
    }

    private fun canNavigateUpDir(): Boolean {
        val sd = Environment.getExternalStorageDirectory()
        return try {
            val cur = currentBrowseDir.canonicalPath
            val root = sd.canonicalPath
            cur != root && cur.startsWith(root) && currentBrowseDir.parentFile != null
        } catch (_: Throwable) {
            currentBrowseDir.parentFile != null
        }
    }

    private fun openUnknownSourcesSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            }
        } catch (_: Throwable) {
        }
        openAppPermissionSettings()
    }

    private fun openAppPermissionSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "Не удалось открыть настройки Android", Toast.LENGTH_SHORT).show()
        }
    }
}
