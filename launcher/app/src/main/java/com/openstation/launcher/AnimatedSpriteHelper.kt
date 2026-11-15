package com.openstation.launcher

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Decodes animated sprite resources (animated GIF/WebP on API 28+) cleanly while falling back
 * to static drawables where unsupported.
 *
 * Strictly gates [AnimatedImageDrawable.start] / [AnimatedImageDrawable.stop] on true visibility,
 * attachment, non-zero alpha, and active window state so hidden overlays (apps drawer, closed
 * control center, closed player sheet, screensaver-covered home) never spin Android's
 * AnimatedImageThread at 60 FPS in the background.
 */
object AnimatedSpriteHelper {

    private class SpriteEntry(
        val resId: Int,
        val drawable: AnimatedImageDrawable
    )

    private val entries = WeakHashMap<ImageView, SpriteEntry>()
    private val attachedViews = ArrayList<WeakReference<ImageView>>(12)
    @Volatile
    private var globalWindowActive = true

    fun loadDrawable(context: Context, resId: Int, autoStart: Boolean = true): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val src = ImageDecoder.createSource(context.resources, resId)
                val d = ImageDecoder.decodeDrawable(src)
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    if (autoStart) d.start() else d.stop()
                }
                return d
            } catch (_: Throwable) {
            }
        }
        return context.getDrawable(resId)
    }

    fun attach(view: ImageView, resId: Int) {
        val prev = entries[view]
        if (prev != null && prev.resId == resId) {
            syncView(view, prev.drawable)
            return
        }
        prev?.drawable?.stop()

        val d = loadDrawable(view.context, resId, autoStart = false)
        view.setImageDrawable(d)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) {
            val isNewView = !entries.containsKey(view)
            entries[view] = SpriteEntry(resId, d)
            if (isNewView) {
                attachedViews.add(WeakReference(view))
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        val entry = entries[view] ?: return
                        syncView(view, entry.drawable)
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        entries[view]?.drawable?.stop()
                    }
                })
            }
            view.post {
                entries[view]?.let { syncView(view, it.drawable) }
            }
        } else {
            entries.remove(view)
        }
    }

    fun syncAll(windowActive: Boolean = globalWindowActive) {
        globalWindowActive = windowActive
        val it = attachedViews.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) {
                it.remove()
                continue
            }
            val entry = entries[v]
            if (entry != null) {
                syncView(v, entry.drawable)
            }
        }
    }

    private fun syncView(view: ImageView, drawable: AnimatedImageDrawable) {
        val shouldAnimate = globalWindowActive &&
            view.isAttachedToWindow &&
            view.windowVisibility == View.VISIBLE &&
            isEffectivelyVisible(view)
        if (shouldAnimate) {
            if (!drawable.isRunning) drawable.start()
        } else {
            if (drawable.isRunning) drawable.stop()
        }
    }

    private fun isEffectivelyVisible(view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.alpha <= 0.02f) return false
        var p: ViewParent? = view.parent
        while (p is View) {
            if (p.visibility != View.VISIBLE || p.alpha <= 0.02f) return false
            p = (p as View).parent
        }
        return true
    }
}

fun ImageView.setSpriteDrawable(resId: Int) {
    AnimatedSpriteHelper.attach(this, resId)
}
