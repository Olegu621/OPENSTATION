package com.openstation.launcher.market

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

object MarketImageLoader {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private val memCache = object : LruCache<String, Bitmap>(64) {}

    fun createMonogramDrawable(title: String, accentColor: Int, density: Float): Drawable {
        val sizePx = (96f * density).toInt().coerceIn(64, 192)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = sizePx * 0.24f
        val rect = RectF(2f, 2f, sizePx - 2f, sizePx - 2f)

        val dark = Color.argb(
            240,
            (Color.red(accentColor) * 0.24f + 10).toInt().coerceIn(0, 255),
            (Color.green(accentColor) * 0.24f + 16).toInt().coerceIn(0, 255),
            (Color.blue(accentColor) * 0.24f + 28).toInt().coerceIn(0, 255)
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dark
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, r, r, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = (2.2f * density).coerceAtLeast(2f)
        }
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // Subtle specular sheen in upper left corner
        val clipPath = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, 255, 255, 255)
        }
        canvas.drawCircle(sizePx * 0.22f, sizePx * 0.14f, sizePx * 0.56f, sheenPaint)
        canvas.restore()

        val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.46f
            isFakeBoldText = true
        }
        val fm = textPaint.fontMetrics
        val baseline = sizePx * 0.5f - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(letter, sizePx * 0.5f, baseline, textPaint)

        return BitmapDrawable(null, bmp)
    }

    fun loadIcon(context: Context, iv: ImageView, url: String, fallback: Drawable) {
        val trimmed = url.trim()
        iv.tag = trimmed
        if (trimmed.isEmpty() || (!trimmed.startsWith("https://") && !trimmed.startsWith("http://"))) {
            iv.setImageDrawable(fallback)
            return
        }
        memCache.get(trimmed)?.let { cached ->
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(fallback)

        val appCtx = context.applicationContext
        executor.execute {
            val bmp = fetchOrReadDisk(appCtx, trimmed)
            if (bmp != null) {
                memCache.put(trimmed, bmp)
                mainHandler.post {
                    if (iv.tag == trimmed) {
                        iv.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun fetchOrReadDisk(context: Context, urlStr: String): Bitmap? {
        val dir = File(context.cacheDir, "market_icons").apply { mkdirs() }
        val key = sha1Hex(urlStr)
        val file = File(dir, "$key.png")
        if (file.isFile && file.length() > 32L) {
            try {
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                if (decoded != null) return decoded
            } catch (_: Throwable) {
            }
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OpenStationMarket/1.4")
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 1_048_576) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 256 || opts.outHeight / sample > 256) {
                sample *= 2
            }
            val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decOpts) ?: return null
            try {
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 92, out)
                }
            } catch (_: Throwable) {
            }
            bmp
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val dig = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
