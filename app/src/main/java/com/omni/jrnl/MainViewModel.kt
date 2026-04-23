package com.omni.jrnl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * Хранит UI-состояние между пересозданиями Activity (поворот экрана,
 * возврат из фона после убийства процесса).
 *
 * Используется SavedStateHandle — данные переживают даже смерть процесса.
 */
class MainViewModel(private val handle: SavedStateHandle) : ViewModel() {

    companion object {
        private const val KEY_VIEW          = "current_view"
        private const val KEY_PLAYBACK_PATH = "playback_path"
        private const val KEY_WAS_RECORDING = "was_recording"
    }

    /**
     * Активный экран SPA-роутера в момент последнего сохранения состояния.
     * По умолчанию: "home".
     */
    var currentView: String
        get()      = handle[KEY_VIEW] ?: "home"
        set(value) { handle[KEY_VIEW] = value }

    /**
     * Путь к последнему открытому файлу воспроизведения.
     * Используется для восстановления playback после пересоздания.
     */
    var currentPlaybackPath: String?
        get()      = handle[KEY_PLAYBACK_PATH]
        set(value) { handle[KEY_PLAYBACK_PATH] = value }

    /**
     * true если Activity уничтожалась во время активной записи.
     * Используется для принудительной остановки зависшего RecordingService.
     */
    var wasRecording: Boolean
        get()      = handle[KEY_WAS_RECORDING] ?: false
        set(value) { handle[KEY_WAS_RECORDING] = value }

    /**
     * true если это повторный запуск (не первый запуск после установки).
     */
    fun isRestoring() = handle.contains(KEY_VIEW)
}
