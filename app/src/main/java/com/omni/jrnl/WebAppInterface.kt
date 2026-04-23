package com.omni.jrnl

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File

/**
 * JavaScript ↔ Kotlin bridge.
 *
 * Все @JavascriptInterface методы вызываются из WebView-потока (не UI).
 * Любая операция, требующая UI-потока, выполняется через `runOnUiThread`
 * в соответствующем callback (в MainActivity).
 */
class WebAppInterface(
    private val context: Context,
    private val recorderManager: RecorderManager,

    private val onRequestRecordPermission: () -> Unit,
    private val onRecordingStarted: () -> Unit,
    private val onRecordingStopped: (filePath: String) -> Unit,
    private val onNavigateTo: (view: String) -> Unit,
    private val onPlaybackStarted: (durationSec: Int) -> Unit,
    private val onPlaybackFinished: () -> Unit,
    private val onSystemPause: () -> Unit,
) {
    companion object {
        private const val TAG = "WebAppInterface"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── MediaPlayer state machine ────────────────────────────────────────────
    private enum class PlayerState {
        IDLE, PREPARING, PREPARED, PLAYING, PAUSED, STOPPED, RELEASED, ERROR
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playerState = PlayerState.IDLE
    private var audioFocusManager: AudioFocusManager? = null
    private var currentSpeed: Float = 1.0f

    // ─── Recording ────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun startRecording() {
        Log.d(TAG, "startRecording() ← JS | state=${recorderManager.state}")
        onRequestRecordPermission()
    }

    /** Called from MainActivity on the UI thread, after permission is granted. */
    fun doStartRecording() {
        Log.d(TAG, "doStartRecording()")
        if (recorderManager.isRecording) {
            Log.w(TAG, "doStartRecording: already recording — ignored")
            return
        }
        val filePath = recorderManager.startRecording()
        if (filePath != null) {
            Log.d(TAG, "Recording started OK → $filePath")
            onRecordingStarted()
        } else {
            Log.e(TAG, "startRecording failed (RecorderManager returned null)")
        }
    }

    @JavascriptInterface
    fun stopRecording() {
        Log.d(TAG, "stopRecording() ← JS | state=${recorderManager.state}")
        if (!recorderManager.isRecording) {
            Log.w(TAG, "stopRecording: not recording — ignored")
            return
        }
        val path = recorderManager.stopRecording()
        if (path != null) {
            Log.d(TAG, "Recording saved: $path")
            onRecordingStopped(path)
        } else {
            Log.e(TAG, "stopRecording returned null (error during save)")
            onRecordingStopped("")
        }
    }

    // ─── Recordings list ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun getRecordingsList(): String {
        Log.d(TAG, "getRecordingsList() ← JS")
        val recordings = recorderManager.getRecordingsList()
        Log.d(TAG, "Found ${recordings.size} recordings")
        val sb = StringBuilder("[")
        recordings.forEachIndexed { i, info ->
            val dur = getFileDurationSec(info.filePath)
            val nameNoExt = info.fileName.removeSuffix(".m4a")
            val dateStr = info.fileName
                .removePrefix("rec_")
                .removeSuffix(".m4a")
                .replace('_', ' ')
            if (i > 0) sb.append(',')
            sb.append("""{"name":"${escJson(nameNoExt)}","path":"${escJson(info.filePath)}","date":"${escJson(dateStr)}","dur":"${fmtTime(dur)}","size":${info.size}}""")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun getFileDurationSec(filePath: String): Int {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(filePath)
            mp.prepare()
            val dur = mp.duration / 1000
            mp.release()
            dur
        } catch (e: Exception) {
            0
        }
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun playRecording(filePath: String) {
        Log.d(TAG, "playRecording() ← JS | path=$filePath")
        mainHandler.post { startPlayback(filePath) }
    }

    @JavascriptInterface
    fun pausePlayback() {
        Log.d(TAG, "pausePlayback() ← JS | state=$playerState")
        mainHandler.post {
            if (playerState == PlayerState.PLAYING) {
                mediaPlayer?.pause()
                playerState = PlayerState.PAUSED
            }
        }
    }

    @JavascriptInterface
    fun resumePlayback() {
        Log.d(TAG, "resumePlayback() ← JS | state=$playerState")
        mainHandler.post {
            if (playerState == PlayerState.PAUSED) {
                mediaPlayer?.start()
                playerState = PlayerState.PLAYING
            }
        }
    }

    @JavascriptInterface
    fun seekTo(posMs: Int) {
        Log.d(TAG, "seekTo($posMs ms) ← JS | state=$playerState")
        mainHandler.post {
            if (playerState == PlayerState.PLAYING || playerState == PlayerState.PAUSED) {
                mediaPlayer?.seekTo(posMs)
            }
        }
    }

    @JavascriptInterface
    fun setPlaybackSpeed(speed: Float) {
        Log.d(TAG, "setPlaybackSpeed($speed) ← JS")
        currentSpeed = speed
        mainHandler.post {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val mp = mediaPlayer ?: return@post
                if (playerState == PlayerState.PLAYING || playerState == PlayerState.PAUSED) {
                    val playing = playerState == PlayerState.PLAYING
                    mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    if (!playing) mp.pause()
                }
            }
        }
    }

    @JavascriptInterface
    fun stopPlayback() {
        Log.d(TAG, "stopPlayback() ← JS")
        mainHandler.post { releasePlayer() }
    }

    @JavascriptInterface
    fun deleteRecording(filePath: String) {
        Log.d(TAG, "deleteRecording() ← JS | path=$filePath")
        mainHandler.post {
            releasePlayer()
            runCatching {
                val f = File(filePath)
                if (f.exists()) { f.delete(); Log.d(TAG, "Deleted: $filePath") }
            }.onFailure { Log.e(TAG, "Delete error: ${it.message}") }
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    /**
     * В SPA-подходе JS сам управляет роутингом через navigate(view).
     * Этот метод остался для обратной совместимости, но из JS он больше
     * не вызывается (нет цикла). Kotlin вызывает evaluateJavascript напрямую.
     */
    @JavascriptInterface
    fun navigateTo(view: String) {
        Log.d(TAG, "navigateTo($view) ← JS")
        onNavigateTo(view)
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun startPlayback(filePath: String) {
        Log.d(TAG, "startPlayback: $filePath | currentState=$playerState")
        releasePlayer()

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            return
        }

        try {
            val mp = MediaPlayer().also { mediaPlayer = it }
            playerState = PlayerState.IDLE

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(filePath)
            playerState = PlayerState.PREPARING

            mp.setOnPreparedListener { player ->
                Log.d(TAG, "MediaPlayer prepared | dur=${player.duration}ms")
                playerState = PlayerState.PLAYING

                // Запрашиваем аудио-фокус
                audioFocusManager = AudioFocusManager(context).apply {
                    request(
                        onPause  = { mainHandler.post { pauseFromSystem() } },
                        onResume = { /* resume handled by system */ }
                    )
                }

                // Применяем скорость воспроизведения
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    player.playbackParams = player.playbackParams.setSpeed(currentSpeed)
                }

                player.start()
                val durSec = player.duration / 1000
                onPlaybackStarted(durSec)
            }

            mp.setOnCompletionListener {
                Log.d(TAG, "MediaPlayer completed")
                playerState = PlayerState.STOPPED
                audioFocusManager?.abandon()
                onPlaybackFinished()
            }

            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                playerState = PlayerState.ERROR
                releasePlayer()
                true
            }

            mp.prepareAsync()
            Log.d(TAG, "prepareAsync() called")
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback exception: ${e.message}", e)
            playerState = PlayerState.ERROR
        }
    }

    private fun pauseFromSystem() {
        if (playerState == PlayerState.PLAYING) {
            Log.d(TAG, "pauseFromSystem()")
            mediaPlayer?.pause()
            playerState = PlayerState.PAUSED
            onSystemPause()
        }
    }

    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer() | state=$playerState")
        audioFocusManager?.abandon()
        audioFocusManager = null
        runCatching {
            if (playerState != PlayerState.IDLE && playerState != PlayerState.RELEASED) {
                mediaPlayer?.stop()
            }
        }
        runCatching { mediaPlayer?.reset() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer  = null
        playerState  = PlayerState.RELEASED
        currentSpeed = 1.0f
    }

    fun release() {
        Log.d(TAG, "WebAppInterface.release()")
        mainHandler.post { releasePlayer() }
    }

    // ─── System bridge ────────────────────────────────────────────────────────

    /**
     * Открывает URL во внешнем браузере.
     * Вызывается из JS: Android.openUrl('https://...')
     */
    @JavascriptInterface
    fun openUrl(url: String) {
        Log.d(TAG, "openUrl() ← JS | url=$url")
        mainHandler.post {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure { Log.e(TAG, "openUrl error: ${it.message}") }
        }
    }

    /**
     * Сворачивает приложение (back с главного экрана).
     * Вызывается из JS: Android.closeApp()
     */
    @JavascriptInterface
    fun closeApp() {
        Log.d(TAG, "closeApp() ← JS")
        mainHandler.post {
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    // ─── Util ─────────────────────────────────────────────────────────────────

    private fun fmtTime(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    private fun escJson(s: String): String =
        s.replace("\\", "\\\\")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "\\r")
}
