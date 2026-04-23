package com.omni.jrnl

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Управляет жизненным циклом MediaRecorder.
 * Гарантирует атомарные переходы между состояниями IDLE ↔ RECORDING.
 */
class RecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "RecorderManager"
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    }

    enum class State { IDLE, RECORDING }

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null

    var state: State = State.IDLE
        private set

    val isRecording: Boolean get() = state == State.RECORDING

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Запускает запись в ExternalFilesDir/Music.
     * Если запись уже идёт — возвращает null и логирует предупреждение.
     * @return абсолютный путь к файлу, или null при ошибке/дублировании
     */
    fun startRecording(): String? {
        if (state == State.RECORDING) {
            Log.w(TAG, "startRecording() ignored — state is already RECORDING")
            return null
        }

        val fileName = "rec_${DATE_FMT.format(Date())}.m4a"
        val storageDir = resolveStorageDir()
        storageDir.mkdirs()
        val file = File(storageDir, fileName)
        currentFilePath = file.absolutePath

        Log.d(TAG, "startRecording() → $currentFilePath")

        return try {
            mediaRecorder = buildMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }
            state = State.RECORDING
            Log.d(TAG, "State → RECORDING")
            currentFilePath
        } catch (e: Exception) {
            Log.e(TAG, "startRecording() failed", e)
            safeRelease()
            state = State.IDLE
            null
        }
    }

    /**
     * Останавливает запись и освобождает MediaRecorder.
     * Если запись не запущена — возвращает null.
     * @return абсолютный путь к сохранённому файлу, или null при ошибке
     */
    fun stopRecording(): String? {
        if (state != State.RECORDING) {
            Log.w(TAG, "stopRecording() ignored — state is $state, not RECORDING")
            return null
        }

        val savedPath = currentFilePath
        Log.d(TAG, "stopRecording() → saving to $savedPath")

        return try {
            mediaRecorder?.stop()
            Log.d(TAG, "MediaRecorder stopped, file saved: $savedPath")
            savedPath
        } catch (e: RuntimeException) {
            Log.e(TAG, "MediaRecorder.stop() failed — deleting corrupt file", e)
            savedPath?.let { File(it).delete() }
            null
        } finally {
            safeRelease()
            state = State.IDLE
            Log.d(TAG, "State → IDLE")
        }
    }

    /**
     * Возвращает список всех записей из внешнего и внутреннего хранилища,
     * отсортированных от новых к старым.
     */
    fun getRecordingsList(): List<RecordingInfo> {
        // Ищем в обоих местах для обратной совместимости
        val dirs = listOfNotNull(
            resolveStorageDir(),
            context.filesDir
        ).distinct()

        return dirs
            .flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a") }?.toList()
                    ?: emptyList()
            }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                RecordingInfo(
                    filePath  = file.absolutePath,
                    fileName  = file.name,
                    timestamp = file.lastModified(),
                    size      = file.length()
                )
            }
    }

    /**
     * Удаляет файл записи.
     * @return true если удалён успешно
     */
    fun deleteRecording(filePath: String): Boolean {
        val file = File(filePath)
        return if (file.exists()) {
            val deleted = file.delete()
            Log.d(TAG, "deleteRecording($filePath) → deleted=$deleted")
            deleted
        } else {
            Log.w(TAG, "deleteRecording: file not found at $filePath")
            false
        }
    }

    /**
     * Безопасно освобождает все ресурсы.
     * Если запись идёт — принудительно останавливает.
     */
    fun release() {
        Log.d(TAG, "release() called, state=$state")
        if (state == State.RECORDING) {
            Log.w(TAG, "release() while RECORDING — forcing stop")
            runCatching { mediaRecorder?.stop() }
                .onFailure { Log.e(TAG, "Forced stop failed", it) }
        }
        safeRelease()
        state = State.IDLE
        Log.d(TAG, "RecorderManager fully released")
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    /** Предпочитает ExternalFilesDir, fallback на filesDir при недоступности. */
    private fun resolveStorageDir(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir

    private fun buildMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun safeRelease() {
        runCatching {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        }.onFailure {
            Log.e(TAG, "Error releasing MediaRecorder", it)
        }
        mediaRecorder = null
        currentFilePath = null
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    data class RecordingInfo(
        val filePath:  String,
        val fileName:  String,
        val timestamp: Long,
        val size:      Long
    )
}
