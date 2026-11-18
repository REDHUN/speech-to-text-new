package com.livespeechrecorder.live_speech_recorder.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

enum class CaptureState {
    UNINITIALIZED,
    RECORDING,
    PAUSED,
    STOPPED
}

/**
 * Manages the single Android AudioRecord capture session.
 *
 * Guarantees that only ONE microphone session is ever opened, capturing raw PCM
 * and feeding it continuously to the PcmAudioDistributor.
 */
class AudioCaptureManager(
    val sampleRate: Int = 16000,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val distributor: PcmAudioDistributor
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    var currentState: CaptureState = CaptureState.UNINITIALIZED
        private set

    var onError: ((code: String, message: String) -> Unit)? = null

    /**
     * Initializes and starts continuous audio capture from the microphone.
     */
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (currentState == CaptureState.RECORDING) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError?.invoke("AUDIO_CONFIG_ERROR", "Invalid AudioRecord configuration with sampleRate: $sampleRate")
            return
        }

        // Allocate buffer for 100ms chunks to achieve low latency
        val bytesPer100ms = (sampleRate * 2 * 100) / 1000
        val bufferSize = max(minBufferSize * 2, bytesPer100ms)

        try {
            // Prefer VOICE_RECOGNITION audio source for speech applications
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to standard MIC source if VOICE_RECOGNITION is rejected
                val micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (micRecord.state != AudioRecord.STATE_INITIALIZED) {
                    micRecord.release()
                    onError?.invoke("AUDIO_RECORD_INIT_FAILED", "Failed to initialize AudioRecord. Microphone may be in use.")
                    return
                }
                audioRecord = micRecord
            } else {
                audioRecord = record
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            isPaused.set(false)
            currentState = CaptureState.RECORDING

            // Start dedicated capture coroutine loop
            captureJob = scope.launch {
                runCaptureLoop(bufferSize)
            }
        } catch (e: SecurityException) {
            onError?.invoke("PERMISSION_DENIED", "RECORD_AUDIO permission not granted: ${e.message}")
        } catch (e: Exception) {
            onError?.invoke("AUDIO_CAPTURE_EXCEPTION", "Exception starting audio capture: ${e.message}")
        }
    }

    /**
     * Continuous audio read loop running on IO thread.
     */
    private fun CoroutineScope.runCaptureLoop(bufferSize: Int) {
        val audioBuffer = ByteArray(bufferSize)

        while (isActive && isRecording.get()) {
            val record = audioRecord ?: break

            if (isPaused.get()) {
                // When paused, we keep the record alive to avoid re-init overhead,
                // but discard frames or sleep briefly
                try {
                    Thread.sleep(50)
                } catch (ignored: InterruptedException) {
                }
                continue
            }

            val readBytes = record.read(audioBuffer, 0, audioBuffer.size)

            if (readBytes > 0) {
                // Single microphone audio distribution to all pipelines
                distributor.dispatch(audioBuffer, readBytes)
            } else {
                when (readBytes) {
                    AudioRecord.ERROR_INVALID_OPERATION -> {
                        onError?.invoke("CAPTURE_INVALID_OPERATION", "AudioRecord read returned ERROR_INVALID_OPERATION")
                    }
                    AudioRecord.ERROR_BAD_VALUE -> {
                        onError?.invoke("CAPTURE_BAD_VALUE", "AudioRecord read returned ERROR_BAD_VALUE")
                    }
                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        onError?.invoke("CAPTURE_DEAD_OBJECT", "AudioRecord dead object; restarting capture...")
                        // Attempt recovery
                        restartCapture()
                        return
                    }
                }
            }
        }
    }

    fun pause() {
        if (currentState == CaptureState.RECORDING) {
            isPaused.set(true)
            currentState = CaptureState.PAUSED
        }
    }

    fun resume() {
        if (currentState == CaptureState.PAUSED) {
            isPaused.set(false)
            currentState = CaptureState.RECORDING
        }
    }

    fun stop() {
        isRecording.set(false)
        isPaused.set(false)
        currentState = CaptureState.STOPPED

        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            // Ignore release errors
        } finally {
            audioRecord = null
        }
    }

    private fun restartCapture() {
        stop()
        startCapture()
    }
}
