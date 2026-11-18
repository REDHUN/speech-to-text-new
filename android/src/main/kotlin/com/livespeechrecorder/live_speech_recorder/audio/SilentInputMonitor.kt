package com.livespeechrecorder.live_speech_recorder.audio

/**
 * Detects a microphone stream that has died without reporting an error.
 *
 * When an audio driver fails, AudioRecord can keep delivering buffers of exact zeros. A working
 * microphone has a noise floor, so sustained digital silence means the stream should be reopened.
 * Some devices do gate quiet input to zero, so the wait grows after each restart that brings no
 * audio back, keeping restarts rare on those devices.
 */
class SilentInputMonitor(private val bytesPerSecond: Int) {

    enum class Action { NONE, RESTART, RECOVERED }

    private var silentBytes = 0L

    /** Restarts since real audio was last seen. */
    var restartsWithoutAudio = 0
        private set

    fun onAudio(buffer: ByteArray, size: Int): Action {
        if (containsSignal(buffer, size)) {
            val recovered = restartsWithoutAudio > 0
            reset()
            return if (recovered) Action.RECOVERED else Action.NONE
        }

        silentBytes += size
        val silentMs = silentBytes * 1000L / bytesPerSecond
        val limitMs = minOf(INITIAL_LIMIT_MS shl minOf(restartsWithoutAudio, MAX_BACKOFF_SHIFT), MAX_LIMIT_MS)
        if (silentMs < limitMs) return Action.NONE

        silentBytes = 0
        restartsWithoutAudio++
        return Action.RESTART
    }

    /** Forgets the current silence, for example while Android is deliberately silencing capture. */
    fun reset() {
        silentBytes = 0
        restartsWithoutAudio = 0
    }

    private fun containsSignal(buffer: ByteArray, size: Int): Boolean {
        for (i in 0 until size) {
            if (buffer[i] != 0.toByte()) return true
        }
        return false
    }

    private companion object {
        const val INITIAL_LIMIT_MS = 1_500L
        const val MAX_LIMIT_MS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5
    }
}
