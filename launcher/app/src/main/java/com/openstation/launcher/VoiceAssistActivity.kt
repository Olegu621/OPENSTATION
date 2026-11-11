package com.openstation.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Intercepts Android ASSIST / VOICE_COMMAND intents (so removed Yandex Alice doesn't break remotes)
 * and forwards the voice trigger to local openstationd (Gemini Live "Алиса" or Wyoming Satellite).
 */
class VoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "Голосовой ассистент «Алиса»", Toast.LENGTH_SHORT).show()
        Thread {
            var handled = false
            try {
                val url = URL("http://127.0.0.1:8080/api/assistant/trigger")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 1000
                    readTimeout = 1000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("""{"reason":"assist_button"}""".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    handled = true
                }
            } catch (_: Throwable) {
            }
            if (!handled) {
                try {
                    val url = URL("http://127.0.0.1:8080/api/wyoming/trigger")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 1000
                        readTimeout = 1000
                        setRequestProperty("Content-Type", "application/json")
                    }
                    conn.outputStream.use { it.write("""{"duration":6000}""".toByteArray()) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Throwable) {
                }
            }
        }.start()
        finish()
    }
}
