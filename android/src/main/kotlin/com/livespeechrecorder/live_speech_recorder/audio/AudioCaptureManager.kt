package com.livespeechrecorder.live_speech_recorder.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.Executor
import kotlin.math.max

data class CaptureStartResult(
    val started: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

interface CaptureListener {
    /** Capture has ended and cannot continue. May be called from any thread. */
    fun onCaptureFatal(code: String, message: String)

    /** Capture continues, but the host should know about the condition. May be called from any thread. */
    fun onCaptureWarning(code: String, message: String)
}

/** A source of 16-bit mono PCM that feeds a [PcmAudioDistributor]. */
interface PcmCaptureSource {
    var listener: CaptureListener?

    /** Opens the microphone. Blocking: call from a background thread. */
    fun start(): CaptureStartResult

    /** Closes the microphone after delivering the final chunk. Blocking: call from a background thread. */
    fun stop()
}

/**
 * Owns the single Android AudioRecord session.
 *
 * Each start() opens a new AudioRecord and each stop() releases it, so a paused session holds no
 * microphone and cannot leak audio captured during the pause into the recording.
 */
class AudioCaptureManager(
    private val sampleRate: Int,
    private val distributor: PcmAudioDistributor
) : PcmCaptureSource {

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 100 ms reads against a one second AudioRecord buffer, so short stalls do not lose audio.
    private val chunkBytes = max(1, sampleRate / 10) * BYTES_PER_SAMPLE
    private val directExecutor = Executor { it.run() }

    @Volatile private var running = false
    @Volatile private var activeRecord: AudioRecord? = null
    @Volatile private var isSilencedByAndroid = false
    private var captureThread: Thread? = null

    override var listener: CaptureListener? = null

    override fun start(): CaptureStartResult {
        if (captureThread != null) return CaptureStartResult(true)

        val opened = openRecord()
        val record = opened.first ?: return opened.second

        running = true
        activeRecord = record
        captureThread = Thread({ runCaptureLoop(record) }, "LiveSpeechCapture").apply {
            isDaemon = true
            start()
        }
        return CaptureStartResult(true)
    }

    override fun stop() {
        val thread = captureThread ?: return
        running = false
        // A blocking read returns within one chunk, which also delivers the tail of the audio.
        thread.join(STOP_GRACE_MS)
        if (thread.isAlive) {
            try {
                activeRecord?.stop()
            } catch (ignored: IllegalStateException) {
            }
            thread.join(STOP_GRACE_MS)
        }
        captureThread = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): Pair<AudioRecord?, CaptureStartResult> {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            return null to failure("AUDIO_CONFIG_ERROR", "Unsupported sample rate: $sampleRate")
        }
        val bufferSize = max(minBufferSize * 2, sampleRate * BYTES_PER_SAMPLE)

        var record: AudioRecord? = null
        try {
            record = createInitialized(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize)
                ?: createInitialized(MediaRecorder.AudioSource.MIC, bufferSize)
                ?: return null to failure(
                    "AUDIO_RECORD_INIT_FAILED",
                    "Could not initialize the microphone."
                )

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return null to failure("MIC_BUSY", "The microphone is in use by another app.")
            }
            observeRecordingConfiguration(record)
            return record to CaptureStartResult(true)
        } catch (e: SecurityException) {
            record?.release()
            return null to failure("PERMISSION_DENIED", "RECORD_AUDIO permission not granted.")
        } catch (e: RuntimeException) {
            record?.release()
            return null to failure("AUDIO_CAPTURE_EXCEPTION", "Could not start capture: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitialized(source: Int, bufferSize: Int): AudioRecord? {
        val record = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        return null
    }

    /** Reports when the OS silences this client (calls, background limits) or changes the input device. */
    private fun observeRecordingConfiguration(record: AudioRecord) {
        val sessionId = record.audioSessionId
        isSilencedByAndroid = false
        record.registerAudioRecordingCallback(directExecutor, object : AudioManager.AudioRecordingCallback() {
            private var silenced = false
            private var deviceId: Int? = null

            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val config = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return

                if (config.isClientSilenced != silenced) {
                    silenced = config.isClientSilenced
                    isSilencedByAndroid = silenced
                    if (silenced) {
                        listener?.onCaptureWarning("MIC_SILENCED", "Android is silencing the microphone. The recording contains silence until it is restored.")
                    } else {
                        listener?.onCaptureWarning("MIC_RESTORED", "Microphone input has been restored.")
                    }
                }

                val newDeviceId = config.audioDevice?.id
                if (newDeviceId != null) {
                    if (deviceId != null && deviceId != newDeviceId) {
                        listener?.onCaptureWarning("AUDIO_ROUTE_CHANGED", "The audio input device changed.")
                    }
                    deviceId = newDeviceId
                }
            }
        })
    }

    private fun runCaptureLoop(initialRecord: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(chunkBytes)
        val silentInputMonitor = SilentInputMonitor(sampleRate * BYTES_PER_SAMPLE)
        var record = initialRecord
        var consecutiveFailures = 0

        try {
            while (true) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    consecutiveFailures = 0
                    distributor.dispatch(buffer, read)
                    if (!running) break

                    if (isSilencedByAndroid) {
                        // Reopening cannot help, and MIC_SILENCED has already been reported.
                        silentInputMonitor.reset()
                        continue
                    }
                    when (silentInputMonitor.onAudio(buffer, read)) {
                        SilentInputMonitor.Action.RESTART -> {
                            Log.w(TAG, "Microphone delivered only digital silence. Reopening it.")
                            if (silentInputMonitor.restartsWithoutAudio == 1) {
                                listener?.onCaptureWarning(
                                    "MIC_INPUT_LOST",
                                    "Microphone input stopped. Reconnecting, so a moment of audio may be missing."
                                )
                            }
                            record = reopenRecord(record, reportRestart = false) ?: return
                        }
                        SilentInputMonitor.Action.RECOVERED -> {
                            Log.i(TAG, "Microphone input recovered.")
                            listener?.onCaptureWarning("MIC_RESTORED", "Microphone input has been restored.")
                        }
                        SilentInputMonitor.Action.NONE -> Unit
                    }
                    continue
                }
                if (!running) break

                if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    record = reopenRecord(record, reportRestart = true) ?: return
                    continue
                }

                consecutiveFailures++
                if (consecutiveFailures >= MAX_READ_FAILURES) {
                    running = false
                    listener?.onCaptureFatal("CAPTURE_FAILED", "Microphone read kept failing (code $read).")
                    break
                }
                Thread.sleep(READ_RETRY_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            running = false
            listener?.onCaptureFatal("CAPTURE_FAILED", "Microphone capture failed: ${e.message}")
        } finally {
            releaseRecord(activeRecord)
            activeRecord = null
        }
    }

    private fun reopenRecord(oldRecord: AudioRecord, reportRestart: Boolean): AudioRecord? {
        releaseRecord(oldRecord)
        activeRecord = null

        var lastFailure = CaptureStartResult(false, "CAPTURE_FAILED", "The microphone connection was lost.")
        for (attempt in 1..MAX_REOPEN_ATTEMPTS) {
            Thread.sleep(REOPEN_BACKOFF_MS * attempt)
            if (!running) return null

            val opened = openRecord()
            val record = opened.first
            if (record != null) {
                activeRecord = record
                if (reportRestart) {
                    listener?.onCaptureWarning("CAPTURE_RESTARTED", "The microphone connection was restored.")
                }
                return record
            }
            lastFailure = opened.second
        }

        running = false
        listener?.onCaptureFatal(
            lastFailure.errorCode ?: "CAPTURE_FAILED",
            lastFailure.errorMessage ?: "The microphone connection was lost."
        )
        return null
    }

    private fun releaseRecord(record: AudioRecord?) {
        try {
            record?.stop()
        } catch (ignored: IllegalStateException) {
        }
        record?.release()
    }

    private fun failure(code: String, message: String) = CaptureStartResult(false, code, message)

    private companion object {
        const val TAG = "LiveSpeechCapture"
        const val BYTES_PER_SAMPLE = 2
        const val STOP_GRACE_MS = 500L
        const val MAX_READ_FAILURES = 25
        const val READ_RETRY_MS = 20L
        const val MAX_REOPEN_ATTEMPTS = 5
        const val REOPEN_BACKOFF_MS = 200L
    }
}
