package com.livespeechrecorder.live_speech_recorder.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight native Voice Activity Detector (VAD).
 *
 * Analyzes raw PCM audio frames to detect speech onset, ongoing speech,
 * silence duration, and sentence/paragraph pause thresholds.
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private var energyThreshold: Double = 0.02,
    private val minSpeechDurationMs: Long = 150,
    private val minSilenceDurationMs: Long = 400
) : PcmConsumer {

    interface VadListener {
        fun onSpeechStart()
        fun onSpeechEnd(silenceDurationMs: Long)
        fun onPauseDetected(durationMs: Long)
    }

    var listener: VadListener? = null

    private var isSpeechActive = false
    private var speechStartTimeMs = 0L
    private var silenceStartTimeMs = 0L
    private var lastFrameTimeMs = 0L
    private var noiseFloor = 0.005

    // Track total processed audio duration
    private var totalProcessedMs = 0L

    override fun onPcmAudio(buffer: ByteArray, readSize: Int) {
        if (readSize < 2) return

        val frameDurationMs = (readSize.toLong() * 1000L) / (sampleRate * 2)
        totalProcessedMs += frameDurationMs

        val energy = calculateRms(buffer, readSize)

        // Adapt noise floor slowly during silence
        if (!isSpeechActive && energy < energyThreshold) {
            noiseFloor = (noiseFloor * 0.95) + (energy * 0.05)
            // Keep threshold dynamic above noise floor
            energyThreshold = max(0.015, noiseFloor * 2.5)
        }

        val speechDetectedInFrame = energy > energyThreshold

        if (speechDetectedInFrame) {
            if (!isSpeechActive) {
                if (speechStartTimeMs == 0L) {
                    speechStartTimeMs = totalProcessedMs
                } else if (totalProcessedMs - speechStartTimeMs >= minSpeechDurationMs) {
                    isSpeechActive = true
                    silenceStartTimeMs = 0L
                    listener?.onSpeechStart()
                }
            } else {
                silenceStartTimeMs = 0L
            }
        } else {
            // Silence frame
            speechStartTimeMs = 0L
            if (isSpeechActive) {
                if (silenceStartTimeMs == 0L) {
                    silenceStartTimeMs = totalProcessedMs
                } else {
                    val silenceDuration = totalProcessedMs - silenceStartTimeMs
                    if (silenceDuration >= minSilenceDurationMs) {
                        isSpeechActive = false
                        listener?.onSpeechEnd(silenceDuration)
                    }
                    listener?.onPauseDetected(silenceDuration)
                }
            } else if (silenceStartTimeMs > 0L) {
                val silenceDuration = totalProcessedMs - silenceStartTimeMs
                listener?.onPauseDetected(silenceDuration)
            }
        }

        lastFrameTimeMs = totalProcessedMs
    }

    fun isSpeechCurrentlyActive(): Boolean = isSpeechActive

    fun getCurrentSilenceDurationMs(): Long {
        if (isSpeechActive || silenceStartTimeMs == 0L) return 0L
        return totalProcessedMs - silenceStartTimeMs
    }

    fun reset() {
        isSpeechActive = false
        speechStartTimeMs = 0L
        silenceStartTimeMs = 0L
        totalProcessedMs = 0L
    }

    private fun calculateRms(buffer: ByteArray, readSize: Int): Double {
        var sum = 0.0
        val sampleCount = readSize / 2
        for (i in 0 until readSize step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += (shortSample * shortSample).toDouble()
        }
        val rms = sqrt(sum / sampleCount)
        return min(1.0, rms / 32767.0)
    }
}
