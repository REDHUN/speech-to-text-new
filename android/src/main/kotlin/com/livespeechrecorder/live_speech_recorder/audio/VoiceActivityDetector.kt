package com.livespeechrecorder.live_speech_recorder.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector.
 *
 * Tracks speech onset and silence length so the transcript can react to natural pauses.
 * [onPcmAudio] runs on the capture thread.
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val initialEnergyThreshold: Double = 0.02,
    private val minSpeechDurationMs: Long = 150,
    private val minSilenceDurationMs: Long = 400
) : PcmConsumer {

    interface VadListener {
        /** @param precedingSilenceMs length of the silence that just ended, or 0 if none was tracked. */
        fun onSpeechStart(precedingSilenceMs: Long)
        fun onSpeechEnd()
    }

    var listener: VadListener? = null

    private var energyThreshold = initialEnergyThreshold
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var speechStartTimeMs = NOT_SET

    @Volatile private var isSpeechActive = false
    @Volatile private var silenceStartTimeMs = NOT_SET
    @Volatile private var totalProcessedMs = 0L

    override fun onPcmAudio(buffer: ByteArray, readSize: Int) {
        val evenSize = readSize and -2
        if (evenSize < 2) return

        val frameStartMs = totalProcessedMs
        totalProcessedMs = frameStartMs + (evenSize.toLong() * 1000L) / (sampleRate * 2)

        val energy = calculateRms(buffer, evenSize)

        if (!isSpeechActive && energy < energyThreshold) {
            noiseFloor = (noiseFloor * 0.95) + (energy * 0.05)
            energyThreshold = max(0.015, noiseFloor * 2.5)
        }

        if (energy > energyThreshold) {
            handleSpeechFrame(frameStartMs)
        } else {
            handleSilenceFrame(frameStartMs)
        }
    }

    private fun handleSpeechFrame(frameStartMs: Long) {
        if (isSpeechActive) {
            silenceStartTimeMs = NOT_SET
            return
        }
        if (speechStartTimeMs == NOT_SET) {
            speechStartTimeMs = frameStartMs
        }
        if (totalProcessedMs - speechStartTimeMs >= minSpeechDurationMs) {
            val silenceStart = silenceStartTimeMs
            val precedingSilence =
                if (silenceStart == NOT_SET) 0L else max(0L, speechStartTimeMs - silenceStart)
            isSpeechActive = true
            silenceStartTimeMs = NOT_SET
            listener?.onSpeechStart(precedingSilence)
        }
    }

    private fun handleSilenceFrame(frameStartMs: Long) {
        speechStartTimeMs = NOT_SET
        if (silenceStartTimeMs == NOT_SET) {
            silenceStartTimeMs = frameStartMs
        }
        if (isSpeechActive && totalProcessedMs - silenceStartTimeMs >= minSilenceDurationMs) {
            isSpeechActive = false
            listener?.onSpeechEnd()
        }
    }

    fun getCurrentSilenceDurationMs(): Long {
        val silenceStart = silenceStartTimeMs
        if (isSpeechActive || silenceStart == NOT_SET) return 0L
        return totalProcessedMs - silenceStart
    }

    fun reset() {
        isSpeechActive = false
        speechStartTimeMs = NOT_SET
        silenceStartTimeMs = NOT_SET
        totalProcessedMs = 0L
        energyThreshold = initialEnergyThreshold
        noiseFloor = INITIAL_NOISE_FLOOR
    }

    private fun calculateRms(buffer: ByteArray, size: Int): Double {
        var sum = 0.0
        val sampleCount = size / 2
        for (i in 0 until size step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort().toInt()
            sum += (shortSample * shortSample).toDouble()
        }
        return min(1.0, sqrt(sum / sampleCount) / 32767.0)
    }

    private companion object {
        const val NOT_SET = -1L
        const val INITIAL_NOISE_FLOOR = 0.005
    }
}
