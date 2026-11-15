package com.openstation.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Standalone Activity entrypoint for voice commands, OAuth deep links, and app-drawer launches.
 * Delegates the entire UI & player logic to [SpatialMusicPlayerSheet] over hardware-accelerated
 * [SpatialGlassBackdropView] so it is visually identical to MainActivity's seamless slide-up sheet.
 */
class YandexMusicActivity : Activity() {

    companion object {
        const val ACTION_MUSIC_CMD = "com.openstation.launcher.MUSIC_CMD"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var spatialBackdrop: SpatialGlassBackdropView
    private lateinit var ivBgFallback: ImageView
    private lateinit var scrimFallback: View
    private lateinit var sheet: SpatialMusicPlayerSheet
    private var hasCoverArt = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonStr = intent?.getStringExtra(YandexMusicPlayer.EXTRA_SNAPSHOT_JSON) ?: return
            try {
                val snap = YmPlayerSnapshot.fromJson(org.json.JSONObject(jsonStr))
                sheet.renderSnapshot(snap)
            } catch (_: Throwable) {}
        }
    }

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::sheet.isInitialized) sheet.updateWebQrInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StationAudioService.ensureStarted(this)
        LiquidGlassKit.ensureInitialized(this)

        val rootFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        val wallBmp = LiquidGlassKit.sharpWallpaper
            ?: BitmapFactory.decodeResource(resources, R.drawable.bg_tv_stage)

        ivBgFallback = ImageView(this).apply {
            if (wallBmp != null && !wallBmp.isRecycled) {
                setImageBitmap(wallBmp)
            } else {
                setImageResource(R.drawable.bg_tv_stage)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        scrimFallback = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#54040710"),
                    Color.parseColor("#2A050811"),
                    Color.parseColor("#66040710")
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(ivBgFallback)
        rootFrame.addView(scrimFallback)

        spatialBackdrop = SpatialGlassBackdropView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (wallBmp != null && !wallBmp.isRecycled) setWallpaperBitmap(wallBmp)
            setSceneState(backdropBlur = 0.86f, backdropDim = 0.36f, orbEnergy = 0.25f, musicAmbientAlpha = 0f)
            onGlStatusListener = { ready, failed ->
                handler.post {
                    val vis = if (ready && !failed) View.GONE else View.VISIBLE
                    ivBgFallback.visibility = vis
                    scrimFallback.visibility = vis
                }
            }
        }
        rootFrame.addView(spatialBackdrop)

        sheet = SpatialMusicPlayerSheet(
            context = this,
            spatialBackdrop = spatialBackdrop,
            closeLabel = "Домой",
            isEmbeddedOverlay = false,
            onCoverBitmapLoaded = { bmp ->
                hasCoverArt = (bmp != null && !bmp.isRecycled)
                if (::spatialBackdrop.isInitialized) {
                    spatialBackdrop.setMusicCoverBitmap(bmp, showAmbient = hasCoverArt)
                    spatialBackdrop.setSceneState(
                        backdropBlur = 0.88f,
                        backdropDim = 0.38f,
                        orbEnergy = if (sheet.lastSnapshot.playing) 0.45f else 0.16f,
                        musicAmbientAlpha = if (hasCoverArt) 0.90f else 0f
                    )
                }
            },
            onCloseRequested = { finish() }
        )
        rootFrame.addView(sheet.rootOverlay)
        setContentView(rootFrame)

        handleIntentExtras(intent)
        sheet.focusDefaultControl()

        try {
            registerReceiver(stateReceiver, IntentFilter(YandexMusicPlayer.ACTION_MUSIC_STATE))
        } catch (_: Throwable) {}
        try {
            registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        } catch (_: Throwable) {}

        sheet.refreshFromService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::spatialBackdrop.isInitialized) spatialBackdrop.onHostResume()
        sheet.refreshFromService()
    }

    override fun onPause() {
        if (::spatialBackdrop.isInitialized) spatialBackdrop.onHostPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::sheet.isInitialized) sheet.release()
        try { unregisterReceiver(stateReceiver) } catch (_: Throwable) {}
        try { unregisterReceiver(networkReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.spatial_hold_fade_in, R.anim.spatial_sheet_out_down)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::sheet.isInitialized && sheet.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIntentExtras(intent: Intent?) {
        val tok = intent?.getStringExtra("oauth_token")
        if (!tok.isNullOrBlank()) {
            intent.removeExtra("oauth_token")
            StationAudioService.instance?.musicPlayer?.setOAuthToken(tok, autoResumeMyWave = false)
            sheet.switchTab("wave")
        }
        val tab = intent?.getStringExtra("tab")
        if (!tab.isNullOrBlank()) {
            intent.removeExtra("tab")
            sheet.switchTab(tab)
        }
        if (intent?.getBooleanExtra("open_login", false) == true) {
            intent.removeExtra("open_login")
            sheet.switchTab("auth")
            sheet.openPassportOAuthDialog()
        }
    }
}
