package com.livespeechrecorder.live_speech_recorder.recognition

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import com.livespeechrecorder.live_speech_recorder.RecordingForegroundService
import com.livespeechrecorder.live_speech_recorder.audio.AudioCaptureManager
import com.livespeechrecorder.live_speech_recorder.audio.PcmAudioDistributor
import com.livespeechrecorder.live_speech_recorder.audio.PcmCaptureSource
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Android services a recording session depends on, separated so that the session's state
 * machine can be tested without a device.
 */
interface SessionPlatform {
    fun runOnMain(task: () -> Unit)

    /** @return A function that cancels the task. */
    fun runOnMainDelayed(delayMs: Long, task: () -> Unit): () -> Unit

    /** Runs blocking work. Tasks run one at a time, in order. */
    fun runInBackground(task: () -> Unit)

    fun hasRecordPermission(): Boolean
    fun startForegroundNotice(title: String, text: String): Boolean
    fun stopForegroundNotice()
    fun createCapture(sampleRate: Int, distributor: PcmAudioDistributor): PcmCaptureSource

    /** @return null when live transcription is not possible on this device. */
    fun createBackend(config: RecognitionConfig): SpeechRecognitionBackend?
}

class AndroidSessionPlatform(private val context: Context) : SessionPlatform {
    private val mainHandler = Handler(Looper.getMainLooper())

    // One worker that exits when idle, so no shutdown is needed while teardown work is queued.
    private val backgroundExecutor = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
    ) { task -> Thread(task, "LiveSpeechSession").apply { isDaemon = true } }

    private var foregroundNoticeActive = false

    override fun runOnMain(task: () -> Unit) {
        mainHandler.post(task)
    }

    override fun runOnMainDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable(task)
        mainHandler.postDelayed(runnable, delayMs)
        return { mainHandler.removeCallbacks(runnable) }
    }

    override fun runInBackground(task: () -> Unit) {
        backgroundExecutor.execute(task)
    }

    override fun hasRecordPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun startForegroundNotice(title: String, text: String): Boolean {
        // Android only allows a microphone service to start while the app is visible.
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)
        if (processState.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return false

        return try {
            RecordingForegroundService.start(context, title, text)
            foregroundNoticeActive = true
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    override fun stopForegroundNotice() {
        if (!foregroundNoticeActive) return
        foregroundNoticeActive = false
        RecordingForegroundService.stop(context)
    }

    override fun createCapture(sampleRate: Int, distributor: PcmAudioDistributor): PcmCaptureSource =
        AudioCaptureManager(sampleRate, distributor)

    override fun createBackend(config: RecognitionConfig): SpeechRecognitionBackend? {
        // Older versions cannot share the recording's audio with the recognizer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        val available = SpeechRecognizer.isRecognitionAvailable(context) ||
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return if (available) AndroidSpeechRecognizerBackend(context, config) else null
    }
}
