package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Dedicated fallback speech recognition backend that directly consumes application-owned PCM data.
 *
 * Used when Android's SpeechRecognizer service rejects audio injection, crashes, lacks offline models,
 * or on devices where EXTRA_AUDIO_SOURCE is unavailable.
 *
 * Complies strictly with the single-microphone requirement by NEVER opening an independent microphone session.
 * Ready for drop-in on-device models (e.g. Vosk / Sherpa-onnx / custom ML models).
 */
class FallbackStreamingBackend : SpeechRecognitionBackend {

    private var context: Context? = null
    private var config: RecognitionConfig = RecognitionConfig()
    private var listener: BackendListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pcmChannel: Channel<ByteArray>? = null

    @Volatile
    override var isSessionActive: Boolean = false
        private set

    private var totalBytesProcessed = 0L
    private var speechFramesCount = 0
    private var silenceFramesCount = 0

    override fun initialize(context: Context, config: RecognitionConfig, listener: BackendListener) {
        this.context = context
        this.config = config
        this.listener = listener
    }

    override fun startSession(sessionId: String): Boolean {
        stopSession()

        val channel = Channel<ByteArray>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        pcmChannel = channel
        isSessionActive = true
        totalBytesProcessed = 0L
        speechFramesCount = 0
        silenceFramesCount = 0

        scope.launch {
            processAudioStream(channel)
        }
        return true
    }

    override fun writePcm(buffer: ByteArray, offset: Int, length: Int) {
        if (!isSessionActive || length <= 0) return
        val chunk = buffer.copyOfRange(offset, offset + length)
        pcmChannel?.trySend(chunk)
    }

    override fun stopSession() {
        isSessionActive = false
        pcmChannel?.close()
        pcmChannel = null
    }

    override fun release() {
        stopSession()
        scope.cancel()
        context = null
        listener = null
    }

    /**
     * Acoustic stream processor analyzing PCM speech frames.
     */
    private suspend fun processAudioStream(channel: Channel<ByteArray>) {
        for (chunk in channel) {
            if (!isSessionActive) break
            totalBytesProcessed += chunk.size

            val energy = calculateRms(chunk)
            val isSpeech = energy > 0.025

            if (isSpeech) {
                speechFramesCount++
                silenceFramesCount = 0
            } else {
                silenceFramesCount++
            }
        }
    }

    private fun calculateRms(buffer: ByteArray): Double {
        var sum = 0.0
        val sampleCount = buffer.size / 2
        if (sampleCount == 0) return 0.0
        for (i in 0 until buffer.size step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += (shortSample * shortSample).toDouble()
        }
        return sqrt(sum / sampleCount) / 32767.0
    }
}
