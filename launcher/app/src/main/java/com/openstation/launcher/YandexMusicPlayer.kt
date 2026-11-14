package com.openstation.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class YandexMusicPlayer(private val context: Context) {

    companion object {
        private const val TAG = "YmNativePlayer"
        const val ACTION_MUSIC_STATE = "com.openstation.launcher.MUSIC_STATE"
        const val EXTRA_SNAPSHOT_JSON = "snapshot_json"
    }

    val api = YandexMusicApi(context)
    private val proxyServer = AesCtrProxyServer()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val netPool = Executors.newCachedThreadPool()

    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null

    private var isPlaying = false
    private var isPaused = false
    private var isBuffering = false
    private var assistantDucked = false
    private var focusDucked = false
    private var pausedByTransientFocus = false

    private var sourceType = "idle"
    private var sourceLabel = "Яндекс Музыка"
    private var waveMood = "any"
    private var waveSeeds: List<String> = listOf("user:onyourwave")
    private var rotorSessionId = ""
    private var radioStartedSent = false
    private var fetchMoreInFlight = false
    private val pendingFeedbacks = ArrayList<JSONObject>()

    private val queue = ArrayList<YmTrack>()
    private var currentIndex = -1
    private var currentTrack: YmTrack? = null
    private var currentTrackStartElapsed = 0L
    private var currentTrackRetriedNormal = false
    private var currentCodecLabel = ""
    private var currentAccount = api.getCachedAccount()
    private var lastError = ""
    private var lastReportedDurMs = 0L

    private val playTokenGen = AtomicLong(0L)
    private val snapshotSeq = AtomicLong(1L)
    private var lastDaemonPushMs = 0L
    private var lastTickerTrackId = ""
    private var savedPausedPosMs = 0L
    private var savedPausedTrackId = ""
    private var released = false

    private val progressTicker = object : Runnable {
        override fun run() {
            if (released) return
            val playingNow = synchronized(lock) { isPlaying && !isPaused }
            if (playingNow) {
                emitSnapshot(forceDaemon = false)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val pauseReleaseRunnable = Runnable {
        if (released) return@Runnable
        worker.submit {
            val releasedIdle = synchronized(lock) {
                val mp = mediaPlayer
                if (mp != null && isPaused && !isPlaying && !isBuffering) {
                    val curPos = try {
                        mp.currentPosition.toLong().coerceAtLeast(0L)
                    } catch (_: Throwable) {
                        savedPausedPosMs
                    }
                    savedPausedPosMs = curPos
                    savedPausedTrackId = currentTrack?.id ?: ""
                    releaseMediaPlayerLocked()
                    abandonFocusLocked()
                    updateMediaSessionLocked()
                    true
                } else {
                    false
                }
            }
            if (releasedIdle) {
                Log.i(TAG, "Released idle paused MediaPlayer to cool audio HAL (savedPos=${savedPausedPosMs}ms)")
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    private fun schedulePauseRelease() {
        mainHandler.removeCallbacks(pauseReleaseRunnable)
        if (!released) {
            mainHandler.postDelayed(pauseReleaseRunnable, 25_000L)
        }
    }

    private fun cancelPauseRelease() {
        mainHandler.removeCallbacks(pauseReleaseRunnable)
    }

    init {
        setupMediaSession()
        netPool.submit {
            if (api.hasToken()) {
                try {
                    val acct = api.refreshAccount()
                    synchronized(lock) { currentAccount = acct }
                } catch (t: Throwable) {
                    Log.w(TAG, "Initial account refresh failed: ${t.message}")
                }
            }
            emitSnapshot(forceDaemon = true)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(pauseReleaseRunnable)
        synchronized(lock) {
            savedPausedPosMs = 0L
            savedPausedTrackId = ""
            releaseMediaPlayerLocked()
            abandonFocusLocked()
        }
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {}
        proxyServer.stop()
        worker.shutdownNow()
        netPool.shutdownNow()
    }

    fun getSnapshot(): YmPlayerSnapshot {
        synchronized(lock) {
            val pos = try {
                val mp = mediaPlayer
                if (mp != null && (isPlaying || isPaused) && !isBuffering) {
                    mp.currentPosition.toLong()
                } else if (isPaused && currentTrack?.id == savedPausedTrackId) {
                    savedPausedPosMs
                } else {
                    0L
                }
            } catch (_: Throwable) {
                if (isPaused && currentTrack?.id == savedPausedTrackId) savedPausedPosMs else 0L
            }
            val dur = try {
                val mp = mediaPlayer
                val d = if (mp != null && (isPlaying || isPaused) && !isBuffering) mp.duration.toLong() else 0L
                if (d > 0L) d else (currentTrack?.durationMs ?: lastReportedDurMs)
            } catch (_: Throwable) { currentTrack?.durationMs ?: 0L }
            if (dur > 0L) lastReportedDurMs = dur

            val startIdx = maxOf(0, currentIndex)
            val preview = queue.drop(startIdx).take(15)
            return YmPlayerSnapshot(
                playing = isPlaying && !isPaused,
                paused = isPaused,
                buffering = isBuffering,
                ducked = assistantDucked,
                sourceType = sourceType,
                sourceLabel = sourceLabel,
                waveMood = waveMood,
                quality = api.getPreferredQuality(),
                codecLabel = currentCodecLabel,
                currentTrack = currentTrack,
                positionMs = pos.coerceAtLeast(0L),
                durationMs = dur.coerceAtLeast(0L),
                queueSize = queue.size,
                currentIndex = currentIndex,
                queuePreview = preview,
                account = currentAccount,
                lastError = lastError,
                seq = snapshotSeq.get()
            )
        }
    }

    private fun setupMediaSession() {
        try {
            val ms = MediaSession(context, "OpenStationMusic")
            ms.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { prev() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            }, mainHandler)
            ms.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            mediaSession = ms
        } catch (t: Throwable) {
            Log.w(TAG, "MediaSession init failed: ${t.message}")
        }
    }

    private fun updateMediaSessionLocked(coverBmp: Bitmap? = null) {
        val ms = mediaSession ?: return
        try {
            val st = when {
                isBuffering -> PlaybackState.STATE_BUFFERING
                isPlaying && !isPaused -> PlaybackState.STATE_PLAYING
                isPaused -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }
            val pos = try {
                mediaPlayer?.currentPosition?.toLong()
                    ?: if (isPaused && currentTrack?.id == savedPausedTrackId) savedPausedPosMs else 0L
            } catch (_: Throwable) {
                if (isPaused && currentTrack?.id == savedPausedTrackId) savedPausedPosMs else 0L
            }
            val pb = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(st, pos, if (st == PlaybackState.STATE_PLAYING) 1.0f else 0f)
                .build()
            ms.setPlaybackState(pb)
            ms.isActive = (st != PlaybackState.STATE_STOPPED)

            val tr = currentTrack
            if (tr != null) {
                val mb = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, tr.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, tr.artists)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, tr.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, tr.durationMs)
                if (coverBmp != null) {
                    mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, coverBmp)
                }
                ms.setMetadata(mb.build())
            }
        } catch (_: Throwable) {}
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                synchronized(lock) {
                    focusDucked = false
                    pausedByTransientFocus = false
                }
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val shouldResume = synchronized(lock) { isPlaying && !isPaused }
                if (shouldResume) {
                    synchronized(lock) { pausedByTransientFocus = true }
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                synchronized(lock) {
                    focusDucked = true
                    applyPlayerVolumeLocked()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val resume = synchronized(lock) {
                    focusDucked = false
                    val r = pausedByTransientFocus
                    pausedByTransientFocus = false
                    applyPlayerVolumeLocked()
                    r
                }
                if (resume) play()
            }
        }
    }

    private fun requestFocusLocked(): Boolean {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Throwable) {
            true
        }
    }

    private fun abandonFocusLocked() {
        try {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Throwable) {}
    }

    private fun applyPlayerVolumeLocked() {
        val mp = mediaPlayer ?: return
        val g = (if (assistantDucked) 0.16f else 1.0f) * (if (focusDucked) 0.25f else 1.0f)
        try {
            mp.setVolume(g, g)
        } catch (_: Throwable) {}
    }

    fun setDuck(ducked: Boolean) {
        synchronized(lock) {
            if (assistantDucked == ducked) return
            assistantDucked = ducked
            applyPlayerVolumeLocked()
        }
    }

    fun playMyWave(moodKey: String = "any", extraSeed: String = "", openUiOnAuthNeeded: Boolean = true) {
        worker.submit {
            val mood = YmWaveMood.fromKey(moodKey)
            val seeds = ArrayList<String>()
            seeds.add("user:onyourwave")
            mood.seed?.let { seeds.add(it) }
            if (extraSeed.isNotBlank() && !seeds.contains(extraSeed)) seeds.add(extraSeed)

            val recentPlayed = synchronized(lock) {
                lastError = ""
                sourceType = "my_wave"
                waveMood = mood.key
                waveSeeds = seeds
                sourceLabel = if (mood == YmWaveMood.ANY) "Моя волна" else "Моя волна · ${mood.label}"
                isBuffering = true
                queue.take(maxOf(0, currentIndex + 1)).takeLast(12).map { it.rotorTrackKey }
            }
            emitSnapshot(forceDaemon = true)

            try {
                if (!api.hasToken()) {
                    throw YandexMusicApi.AuthException(401, "Требуется вход в аккаунт Яндекс")
                }
                if (!api.getCachedAccount().loggedIn) {
                    val acct = api.refreshAccount()
                    synchronized(lock) { currentAccount = acct }
                }
                val batch = api.startMyWave(seeds, recentPlayed)
                if (batch.promoBan) {
                    throw Exception("Моя волна временно ограничена для аккаунта (теневой бан). Попробуйте поиск или Мне нравится.")
                }
                if (batch.tracks.isEmpty()) {
                    throw Exception("Моя волна вернула пустую очередь")
                }
                synchronized(lock) {
                    rotorSessionId = batch.sessionId
                    radioStartedSent = false
                    pendingFeedbacks.clear()
                    queue.clear()
                    queue.addAll(batch.tracks)
                    currentIndex = 0
                    lastError = ""
                }
                // Send radioStarted feedback async
                val startFb = api.buildFeedbackPayload("radioStarted", null, null)
                netPool.submit { api.sendFeedback(batch.sessionId, startFb) }
                synchronized(lock) { radioStartedSent = true }

                startTrackAtLocked(0)
            } catch (ae: YandexMusicApi.AuthException) {
                handleAuthFailure(ae.message ?: "Нужна авторизация в Яндекс Музыке", openUiOnAuthNeeded)
            } catch (t: Throwable) {
                Log.w(TAG, "playMyWave error: ${t.message}")
                synchronized(lock) {
                    isBuffering = false
                    isPlaying = false
                    lastError = t.message ?: "Не удалось запустить Мою волну"
                }
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun playQuery(query: String, openUiOnAuthNeeded: Boolean = true) {
        val q = query.trim()
        if (q.isEmpty()) {
            playMyWave("any", "", openUiOnAuthNeeded)
            return
        }
        worker.submit {
            synchronized(lock) {
                lastError = ""
                isBuffering = true
                sourceType = "search"
                sourceLabel = "Поиск: $q"
                rotorSessionId = ""
            }
            emitSnapshot(forceDaemon = true)
            try {
                val tracks = api.searchTracks(q, limit = 25)
                if (tracks.isEmpty()) {
                    throw Exception("По запросу «$q» ничего не найдено")
                }
                synchronized(lock) {
                    queue.clear()
                    queue.addAll(tracks)
                    currentIndex = 0
                    lastError = ""
                }
                startTrackAtLocked(0)
            } catch (ae: YandexMusicApi.AuthException) {
                handleAuthFailure(ae.message ?: "Нужна авторизация в Яндекс Музыке", openUiOnAuthNeeded)
            } catch (t: Throwable) {
                Log.w(TAG, "playQuery($q) error: ${t.message}")
                synchronized(lock) {
                    isBuffering = false
                    isPlaying = false
                    lastError = t.message ?: "Ошибка поиска"
                }
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun playQueueIndex(index: Int) {
        worker.submit {
            val valid = synchronized(lock) { index in queue.indices }
            if (valid) startTrackAtLocked(index)
        }
    }

    fun playTrackList(tracks: List<YmTrack>, startIndex: Int, srcType: String, srcLabel: String) {
        if (tracks.isEmpty()) return
        worker.submit {
            val idx = startIndex.coerceIn(0, tracks.size - 1)
            synchronized(lock) {
                lastError = ""
                sourceType = srcType
                sourceLabel = srcLabel
                rotorSessionId = if (srcType == "my_wave") rotorSessionId else ""
                queue.clear()
                queue.addAll(tracks)
                currentIndex = idx
            }
            startTrackAtLocked(idx)
        }
    }

    fun playLikedTracks(openUiOnAuthNeeded: Boolean = true) {
        worker.submit {
            synchronized(lock) {
                lastError = ""
                isBuffering = true
                sourceType = "likes"
                sourceLabel = "Мне нравится"
                rotorSessionId = ""
            }
            emitSnapshot(forceDaemon = true)
            try {
                if (!api.hasToken()) throw YandexMusicApi.AuthException(401, "Требуется вход в Яндекс Музыку")
                var acct = api.getCachedAccount()
                if (!acct.loggedIn) {
                    acct = api.refreshAccount()
                    synchronized(lock) { currentAccount = acct }
                }
                val tracks = api.getLikedCollection(acct.uid).tracks
                if (tracks.isEmpty()) throw Exception("Список «Мне нравится» пуст")
                synchronized(lock) {
                    queue.clear()
                    queue.addAll(tracks)
                    currentIndex = 0
                    lastError = ""
                }
                startTrackAtLocked(0)
            } catch (ae: YandexMusicApi.AuthException) {
                handleAuthFailure(ae.message ?: "Нужна авторизация в Яндекс Музыке", openUiOnAuthNeeded)
            } catch (t: Throwable) {
                synchronized(lock) {
                    isBuffering = false
                    lastError = t.message ?: "Не удалось загрузить Мне нравится"
                }
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun playChartTracks() {
        worker.submit {
            synchronized(lock) {
                lastError = ""
                isBuffering = true
                sourceType = "chart"
                sourceLabel = "Топ Чарт"
                rotorSessionId = ""
            }
            emitSnapshot(forceDaemon = true)
            try {
                val (title, tracks) = api.getChartTracks()
                if (tracks.isEmpty()) throw Exception("Чарт пуст")
                synchronized(lock) {
                    sourceLabel = title
                    queue.clear()
                    queue.addAll(tracks)
                    currentIndex = 0
                    lastError = ""
                }
                startTrackAtLocked(0)
            } catch (t: Throwable) {
                synchronized(lock) {
                    isBuffering = false
                    lastError = t.message ?: "Не удалось загрузить Чарт"
                }
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun setOAuthToken(rawToken: String, autoResumeMyWave: Boolean = false) {
        worker.submit {
            val tok = YandexMusicApi.normalizeOAuthToken(rawToken)
            if (tok.isEmpty()) {
                synchronized(lock) { lastError = "Пустой OAuth токен" }
                emitSnapshot(forceDaemon = true)
                return@submit
            }
            api.saveToken(tok)
            try {
                val acct = api.refreshAccount()
                synchronized(lock) {
                    currentAccount = acct
                    lastError = ""
                }
                emitSnapshot(forceDaemon = true)
                if (autoResumeMyWave) {
                    playMyWave(waveMood, "", openUiOnAuthNeeded = false)
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    currentAccount = YmAccount(loggedIn = false)
                    lastError = t.message ?: "Токен не принят Яндексом"
                }
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun logout() {
        worker.submit {
            stopInternalLocked()
            api.clearAuth()
            synchronized(lock) {
                currentAccount = YmAccount(loggedIn = false)
                queue.clear()
                currentTrack = null
                currentIndex = -1
                rotorSessionId = ""
                lastError = ""
            }
            emitSnapshot(forceDaemon = true)
        }
    }

    fun play() {
        cancelPauseRelease()
        worker.submit {
            var resumeIdx = -1
            var resumePosMs = 0L
            val action = synchronized(lock) {
                val mp = mediaPlayer
                if (mp != null && isPaused) {
                    requestFocusLocked()
                    try {
                        mp.start()
                        isPlaying = true
                        isPaused = false
                        savedPausedPosMs = 0L
                        savedPausedTrackId = ""
                        applyPlayerVolumeLocked()
                        updateMediaSessionLocked()
                        1
                    } catch (_: Throwable) {
                        0
                    }
                } else if (currentTrack != null && currentIndex in queue.indices && mp == null) {
                    resumeIdx = currentIndex
                    resumePosMs = if (isPaused && currentTrack?.id == savedPausedTrackId && savedPausedPosMs > 1500L) {
                        savedPausedPosMs
                    } else {
                        0L
                    }
                    2
                } else if (queue.isEmpty()) {
                    3
                } else {
                    0
                }
            }
            when (action) {
                1 -> {
                    startProgressTicker()
                    emitSnapshot(forceDaemon = true)
                }
                2 -> startTrackAtLocked(resumeIdx, resumeSeekMs = resumePosMs)
                3 -> playMyWave("any", "", openUiOnAuthNeeded = true)
            }
        }
    }

    fun pause() {
        worker.submit {
            val changed = synchronized(lock) {
                val mp = mediaPlayer
                if (mp != null && isPlaying && !isPaused) {
                    val curPos = try {
                        mp.currentPosition.toLong().coerceAtLeast(0L)
                    } catch (_: Throwable) {
                        savedPausedPosMs
                    }
                    savedPausedPosMs = curPos
                    savedPausedTrackId = currentTrack?.id ?: ""
                    try {
                        mp.pause()
                    } catch (_: Throwable) {}
                    isPlaying = false
                    isPaused = true
                    updateMediaSessionLocked()
                    true
                } else {
                    false
                }
            }
            if (changed) {
                stopProgressTicker()
                schedulePauseRelease()
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun togglePlayPause() {
        val activePlaying = synchronized(lock) { isPlaying && !isPaused }
        if (activePlaying) pause() else play()
    }

    fun stop() {
        cancelPauseRelease()
        worker.submit {
            stopInternalLocked()
            emitSnapshot(forceDaemon = true)
        }
    }

    private fun stopInternalLocked() {
        cancelPauseRelease()
        playTokenGen.incrementAndGet()
        stopProgressTicker()
        synchronized(lock) {
            releaseMediaPlayerLocked()
            abandonFocusLocked()
            isPlaying = false
            isPaused = false
            isBuffering = false
            savedPausedPosMs = 0L
            savedPausedTrackId = ""
            lastReportedDurMs = 0L
            updateMediaSessionLocked()
        }
    }

    fun next() {
        cancelPauseRelease()
        worker.submit {
            val prevTrack: YmTrack?
            val sid: String
            val playedSec: Double
            val nextIdx: Int
            synchronized(lock) {
                prevTrack = currentTrack
                sid = rotorSessionId
                playedSec = ((SystemClock.elapsedRealtime() - currentTrackStartElapsed).coerceAtLeast(0L)) / 1000.0
                if (queue.isEmpty()) {
                    nextIdx = -1
                } else if (currentIndex + 1 < queue.size) {
                    nextIdx = currentIndex + 1
                } else {
                    nextIdx = -1
                }
            }
            if (prevTrack != null && sid.isNotEmpty()) {
                enqueueRotorFeedback(sid, "skip", prevTrack, playedSec = playedSec)
            }
            if (nextIdx >= 0) {
                startTrackAtLocked(nextIdx)
            } else if (synchronized(lock) { sourceType == "my_wave" }) {
                // Force refill or restart wave
                playMyWave(synchronized(lock) { waveMood }, "", openUiOnAuthNeeded = true)
            } else if (synchronized(lock) { queue.isNotEmpty() }) {
                startTrackAtLocked(0)
            }
        }
    }

    fun prev() {
        cancelPauseRelease()
        worker.submit {
            val curPos = synchronized(lock) {
                try {
                    mediaPlayer?.currentPosition
                        ?: if (isPaused && currentTrack?.id == savedPausedTrackId) savedPausedPosMs.toInt() else 0
                } catch (_: Throwable) { 0 }
            }
            if (curPos > 4500) {
                seekTo(0L)
                return@submit
            }
            val prevIdx = synchronized(lock) {
                if (currentIndex > 0) currentIndex - 1 else -1
            }
            if (prevIdx >= 0) {
                startTrackAtLocked(prevIdx)
            } else {
                seekTo(0L)
            }
        }
    }

    fun seekTo(posMs: Long) {
        worker.submit {
            var updated = false
            synchronized(lock) {
                val mp = mediaPlayer
                if (mp != null) {
                    try {
                        val dur = mp.duration.toLong()
                        val tgt = if (dur > 0L) posMs.coerceIn(0L, dur) else posMs.coerceAtLeast(0L)
                        mp.seekTo(tgt.toInt())
                        updateMediaSessionLocked()
                        updated = true
                    } catch (_: Throwable) {}
                } else if (isPaused && currentTrack != null) {
                    val dur = lastReportedDurMs.coerceAtLeast(currentTrack?.durationMs ?: 0L)
                    savedPausedPosMs = if (dur > 0L) posMs.coerceIn(0L, dur) else posMs.coerceAtLeast(0L)
                    savedPausedTrackId = currentTrack?.id ?: ""
                    updateMediaSessionLocked()
                    updated = true
                }
            }
            if (updated) {
                emitSnapshot(forceDaemon = false)
            }
        }
    }

    fun toggleLikeCurrent() {
        worker.submit {
            val tr: YmTrack?
            val sid: String
            var uid: String
            synchronized(lock) {
                tr = currentTrack
                sid = rotorSessionId
                uid = currentAccount.uid
            }
            if (tr == null) return@submit
            if (!api.hasToken()) {
                handleAuthFailure("Войдите в аккаунт для оценки треков", openUiOnAuthNeeded = true)
                return@submit
            }
            if (uid.isBlank()) {
                try {
                    val acct = api.refreshAccount()
                    synchronized(lock) { currentAccount = acct }
                    uid = acct.uid
                } catch (_: Throwable) {}
            }
            val newLiked = !tr.liked
            val ok = api.setTrackLiked(uid, tr.id, newLiked)
            if (ok) {
                val updated = tr.copy(liked = newLiked)
                synchronized(lock) {
                    currentTrack = updated
                    if (currentIndex in queue.indices && queue[currentIndex].id == updated.id) {
                        queue[currentIndex] = updated
                    }
                }
                if (sid.isNotEmpty()) {
                    enqueueRotorFeedback(sid, if (newLiked) "like" else "unlike", updated)
                }
                pushTickerToDaemon(if (newLiked) "♥ ${updated.artists} - ${updated.title}" else "♡ Лайк снят")
                emitSnapshot(forceDaemon = true)
            }
        }
    }

    fun dislikeCurrent() {
        worker.submit {
            val tr: YmTrack?
            val sid: String
            var uid: String
            val playedSec: Double
            synchronized(lock) {
                tr = currentTrack
                sid = rotorSessionId
                uid = currentAccount.uid
                playedSec = ((SystemClock.elapsedRealtime() - currentTrackStartElapsed).coerceAtLeast(0L)) / 1000.0
            }
            if (tr == null) return@submit
            if (!api.hasToken()) {
                handleAuthFailure("Войдите в аккаунт для настройки волны", openUiOnAuthNeeded = true)
                return@submit
            }
            if (uid.isBlank()) {
                try {
                    val acct = api.refreshAccount()
                    synchronized(lock) { currentAccount = acct }
                    uid = acct.uid
                } catch (_: Throwable) {}
            }
            api.dislikeTrack(uid, tr.id)
            if (sid.isNotEmpty()) {
                enqueueRotorFeedback(sid, "dislike", tr, playedSec = playedSec)
            }
            next()
        }
    }

    private fun startTrackAtLocked(
        index: Int,
        forceNormalQuality: Boolean = false,
        resumeSeekMs: Long = 0L
    ) {
        cancelPauseRelease()
        val tok = playTokenGen.incrementAndGet()
        val track: YmTrack
        val sid: String
        val needMore: Boolean
        val queueTailIds: List<String>

        synchronized(lock) {
            if (index !in queue.indices) {
                isPlaying = false
                isPaused = false
                isBuffering = false
                savedPausedPosMs = 0L
                savedPausedTrackId = ""
                return
            }
            track = queue[index]
            currentIndex = index
            currentTrack = track
            currentTrackRetriedNormal = forceNormalQuality
            currentTrackStartElapsed = SystemClock.elapsedRealtime()
            lastReportedDurMs = track.durationMs
            savedPausedPosMs = 0L
            savedPausedTrackId = ""
            currentCodecLabel = ""
            lastError = ""
            isBuffering = true
            isPaused = false
            releaseMediaPlayerLocked()
            sid = rotorSessionId
            needMore = (sourceType == "my_wave" && sid.isNotEmpty() && (queue.size - 1 - index) <= 2 && !fetchMoreInFlight)
            if (needMore) fetchMoreInFlight = true
            queueTailIds = queue.drop(index).map { it.rotorTrackKey }
            updateMediaSessionLocked()
        }

        emitSnapshot(forceDaemon = true)

        if (needMore) {
            netPool.submit { refillRotorQueue(sid, queueTailIds) }
        }

        netPool.submit {
            try {
                if (playTokenGen.get() != tok || released) return@submit
                val stream = api.resolveStream(track.id, forceNormalQuality = forceNormalQuality)
                if (playTokenGen.get() != tok || released) return@submit

                val playUrl = if (stream.keyHex.length == 32) {
                    proxyServer.registerStream(stream.url, stream.keyHex, stream.codec)
                } else {
                    stream.url
                }

                synchronized(lock) {
                    if (playTokenGen.get() != tok || released) return@submit
                    currentCodecLabel = stream.label
                    requestFocusLocked()

                    val mp = MediaPlayer()
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                    mp.setOnPreparedListener { readyMp ->
                        if (playTokenGen.get() != tok || released) {
                            try { readyMp.release() } catch (_: Throwable) {}
                            return@setOnPreparedListener
                        }
                        synchronized(lock) {
                            isBuffering = false
                            isPlaying = true
                            isPaused = false
                            currentTrackStartElapsed = SystemClock.elapsedRealtime()
                            val durMs = readyMp.duration.toLong().coerceAtLeast(track.durationMs)
                            lastReportedDurMs = durMs
                            if (resumeSeekMs > 0L) {
                                try {
                                    val tgt = if (durMs > 1200L) resumeSeekMs.coerceIn(0L, durMs - 1000L) else resumeSeekMs
                                    readyMp.seekTo(tgt.toInt())
                                } catch (_: Throwable) {}
                            }
                            applyPlayerVolumeLocked()
                            readyMp.start()
                            updateMediaSessionLocked()
                        }
                        startProgressTicker()
                        emitSnapshot(forceDaemon = true)
                        if (track.id != lastTickerTrackId) {
                            lastTickerTrackId = track.id
                            pushTickerToDaemon("♪ ${track.artists} - ${track.title}")
                        }
                        if (sid.isNotEmpty()) {
                            enqueueRotorFeedback(sid, "trackStarted", track)
                        }
                        loadCoverForMediaSession(track, tok)
                    }
                    mp.setOnCompletionListener {
                        if (playTokenGen.get() != tok || released) return@setOnCompletionListener
                        val playedSec = ((SystemClock.elapsedRealtime() - currentTrackStartElapsed).coerceAtLeast(0L)) / 1000.0
                        val totalSec = track.durationMs / 1000.0
                        if (sid.isNotEmpty()) {
                            enqueueRotorFeedback(sid, "trackFinished", track, playedSec = playedSec, totalSec = totalSec)
                        }
                        next()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error what=$what extra=$extra for ${track.id}")
                        if (playTokenGen.get() != tok || released) return@setOnErrorListener true
                        worker.submit {
                            if (!currentTrackRetriedNormal && !forceNormalQuality) {
                                startTrackAtLocked(index, forceNormalQuality = true)
                            } else {
                                synchronized(lock) {
                                    isBuffering = false
                                    lastError = "Ошибка воспроизведения (${track.title})"
                                }
                                emitSnapshot(forceDaemon = true)
                                next()
                            }
                        }
                        true
                    }
                    mediaPlayer = mp
                    mp.setDataSource(playUrl)
                    mp.prepareAsync()
                }
            } catch (ae: YandexMusicApi.AuthException) {
                if (playTokenGen.get() == tok) {
                    handleAuthFailure(ae.message ?: "Нужен вход в Яндекс Музыку", openUiOnAuthNeeded = false)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "resolveStream(${track.id}) failed: ${t.message}")
                if (playTokenGen.get() == tok) {
                    synchronized(lock) {
                        isBuffering = false
                        isPlaying = false
                        lastError = t.message ?: "Ошибка загрузки трека"
                    }
                    emitSnapshot(forceDaemon = true)
                }
            }
        }
    }

    private fun releaseMediaPlayerLocked() {
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try { mp.setOnPreparedListener(null) } catch (_: Throwable) {}
            try { mp.setOnCompletionListener(null) } catch (_: Throwable) {}
            try { mp.setOnErrorListener(null) } catch (_: Throwable) {}
            try { if (mp.isPlaying) mp.stop() } catch (_: Throwable) {}
            try { mp.reset() } catch (_: Throwable) {}
            try { mp.release() } catch (_: Throwable) {}
        }
    }

    private fun refillRotorQueue(sessionId: String, currentTailIds: List<String>) {
        try {
            val fbs = synchronized(lock) {
                val copy = ArrayList(pendingFeedbacks)
                pendingFeedbacks.clear()
                copy
            }
            val batch = api.nextWaveTracks(sessionId, currentTailIds, fbs)
            synchronized(lock) {
                if (rotorSessionId == sessionId && batch.tracks.isNotEmpty()) {
                    val existingIds = queue.drop(maxOf(0, currentIndex)).map { it.id }.toHashSet()
                    for (t in batch.tracks) {
                        if (!existingIds.contains(t.id)) {
                            queue.add(t)
                        }
                    }
                }
                fetchMoreInFlight = false
            }
            emitSnapshot(forceDaemon = false)
        } catch (t: Throwable) {
            Log.w(TAG, "refillRotorQueue failed: ${t.message}")
            synchronized(lock) { fetchMoreInFlight = false }
        }
    }

    private fun enqueueRotorFeedback(
        sessionId: String,
        type: String,
        track: YmTrack,
        playedSec: Double? = null,
        totalSec: Double? = null
    ) {
        val fb = api.buildFeedbackPayload(type, track.batchId, track.rotorTrackKey, playedSec, totalSec)
        synchronized(lock) {
            pendingFeedbacks.add(fb)
            if (pendingFeedbacks.size > 20) pendingFeedbacks.removeAt(0)
        }
        netPool.submit {
            api.sendFeedback(sessionId, fb)
        }
    }

    private fun handleAuthFailure(msg: String, openUiOnAuthNeeded: Boolean) {
        synchronized(lock) {
            isBuffering = false
            isPlaying = false
            isPaused = false
            currentAccount = currentAccount.copy(loggedIn = false)
            lastError = "AUTH_REQUIRED: $msg"
        }
        pushTickerToDaemon("Войдите в Я.Музыку")
        emitSnapshot(forceDaemon = true)
        if (openUiOnAuthNeeded) {
            try {
                val i = Intent(context, YandexMusicActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("tab", "auth")
                }
                context.startActivity(i)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot open YandexMusicActivity for auth: ${t.message}")
            }
        }
    }

    private fun loadCoverForMediaSession(track: YmTrack, tok: Long) {
        val url = track.coverUrl(300)
        if (url.isBlank()) return
        netPool.submit {
            val bmp = api.loadCoverBitmap(url, 300) ?: return@submit
            if (playTokenGen.get() == tok && !released) {
                synchronized(lock) { updateMediaSessionLocked(bmp) }
            }
        }
    }

    private fun startProgressTicker() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.postDelayed(progressTicker, 1000L)
    }

    private fun stopProgressTicker() {
        mainHandler.removeCallbacks(progressTicker)
    }

    fun emitSnapshot(forceDaemon: Boolean) {
        snapshotSeq.incrementAndGet()
        val snap = getSnapshot()
        val jsonStr = snap.toJson().toString()

        try {
            context.sendBroadcast(Intent(ACTION_MUSIC_STATE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_SNAPSHOT_JSON, jsonStr)
            })
        } catch (_: Throwable) {}

        val now = SystemClock.elapsedRealtime()
        if (forceDaemon || (now - lastDaemonPushMs) >= 3000L) {
            lastDaemonPushMs = now
            netPool.submit { postJsonToDaemon("http://127.0.0.1:8080/api/music/state", jsonStr) }
        }
    }

    private fun pushTickerToDaemon(text: String) {
        if (text.isBlank()) return
        netPool.submit {
            val payload = JSONObject()
                .put("text", text)
                .put("durationMs", 4800)
                .put("r", 0)
                .put("g", 220)
                .put("b", 255)
                .toString()
            postJsonToDaemon("http://127.0.0.1:8080/api/screen/text", payload)
        }
    }

    private fun postJsonToDaemon(urlStr: String, jsonBody: String) {
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 500
                readTimeout = 600
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {}
    }
}
