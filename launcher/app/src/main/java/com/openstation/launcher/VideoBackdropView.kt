package com.openstation.launcher

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Muted hardware-decoded screensaver video rendered directly via SurfaceView HWC plane. */
class VideoBackdropView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var player: MediaPlayer? = null
    private var surfaceReady = false
    private var running = false
    private var prepared = false
    var onFirstFrameReady: (() -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    fun setRunning(enabled: Boolean) {
        running = enabled
        visibility = if (enabled) VISIBLE else GONE
        if (!enabled) {
            releasePlayer()
            return
        }
        val p = player
        if (p != null && prepared) {
            try {
                p.start()
            } catch (_: IllegalStateException) {
                releasePlayer()
            }
        } else if (surfaceReady && p == null) {
            createPlayer(holder)
        }
    }

    private fun createPlayer(surfaceHolder: SurfaceHolder) {
        releasePlayer()
        try {
            val p = MediaPlayer()
            player = p
            p.setDisplay(surfaceHolder)
            p.setVolume(0f, 0f)
            p.isLooping = true
            p.setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.saver_earth}"))
            p.setOnInfoListener { mp, what, _ ->
                if (mp === player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    onFirstFrameReady?.invoke()
                }
                false
            }
            p.setOnPreparedListener {
                if (it !== player) return@setOnPreparedListener
                prepared = true
                if (running) {
                    it.start()
                    postDelayed({ if (running && player === it) onFirstFrameReady?.invoke() }, 90L)
                }
            }
            p.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            p.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        prepared = false
        val old = player
        player = null
        try {
            old?.release()
        } catch (_: Exception) {
        }
    }

    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
        surfaceReady = true
        if (running) createPlayer(surfaceHolder)
    }

    override fun surfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
        surfaceReady = false
        releasePlayer()
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }
}
