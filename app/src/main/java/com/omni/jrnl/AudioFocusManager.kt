package com.omni.jrnl

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Управляет AudioFocus для воспроизведения медиа.
 *
 * Логика:
 *  - При старте воспроизведения запрашиваем AUDIOFOCUS_GAIN
 *  - LOSS / LOSS_TRANSIENT → pauseCallback (пауза)
 *  - GAIN → resumeCallback (возобновление, опционально)
 *  - LOSS_TRANSIENT_CAN_DUCK → приглушаем (не паузируем — для фоновых звуков)
 *
 * Совместимо с Android 10+ (API 29+).
 */
class AudioFocusManager(context: Context) {

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler   = Handler(Looper.getMainLooper())

    private var focusRequest:   AudioFocusRequest? = null
    private var onPauseCallback:  (() -> Unit)?    = null
    private var onResumeCallback: (() -> Unit)?    = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "onAudioFocusChange: $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Focus GAINED → resuming")
                mainHandler.post { onResumeCallback?.invoke() }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Focus LOSS (permanent) → pausing")
                mainHandler.post { onPauseCallback?.invoke() }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Focus LOSS_TRANSIENT (phone call etc.) → pausing")
                mainHandler.post { onPauseCallback?.invoke() }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Другое приложение воспроизводит звук в фоне.
                // Для голосового журнала лучше паузировать, а не приглушать.
                Log.d(TAG, "Focus LOSS_CAN_DUCK → pausing")
                mainHandler.post { onPauseCallback?.invoke() }
            }
        }
    }

    /**
     * Запрашивает аудио-фокус перед началом воспроизведения.
     *
     * @param onPause  вызывается, когда система требует остановки (звонок, другая музыка)
     * @param onResume вызывается, когда фокус возвращается (опционально)
     * @return true если фокус предоставлен немедленно
     */
    fun request(
        onPause:  () -> Unit,
        onResume: (() -> Unit)? = null
    ): Boolean {
        onPauseCallback  = onPause
        onResumeCallback = onResume

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()

        focusRequest = req
        val result  = audioManager.requestAudioFocus(req)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        Log.d(TAG, "requestAudioFocus → ${if (granted) "GRANTED" else "DENIED (result=$result)"}")
        return granted
    }

    /**
     * Освобождает аудио-фокус.
     * Вызывать при остановке воспроизведения или в onDestroy().
     */
    fun abandon() {
        focusRequest?.let { req ->
            audioManager.abandonAudioFocusRequest(req)
            Log.d(TAG, "abandonAudioFocusRequest() called")
        }
        focusRequest    = null
        onPauseCallback = null
        onResumeCallback = null
    }
}
