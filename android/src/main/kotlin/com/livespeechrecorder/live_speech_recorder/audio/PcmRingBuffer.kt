package com.livespeechrecorder.live_speech_recorder.audio

/**
 * Thread-safe ring holding the most recent PCM bytes.
 */
class PcmRingBuffer(capacityBytes: Int) {
    private val buffer = ByteArray(capacityBytes)
    private var head = 0
    private var size = 0

    @Synchronized
    fun append(source: ByteArray, length: Int) {
        if (buffer.isEmpty() || length <= 0) return

        val toCopy = minOf(length, buffer.size)
        val sourceOffset = length - toCopy
        val firstPart = minOf(toCopy, buffer.size - head)
        System.arraycopy(source, sourceOffset, buffer, head, firstPart)
        System.arraycopy(source, sourceOffset + firstPart, buffer, 0, toCopy - firstPart)

        head = (head + toCopy) % buffer.size
        size = minOf(buffer.size, size + toCopy)
    }

    /** Returns the buffered bytes, oldest first, or null when empty. */
    @Synchronized
    fun snapshot(): ByteArray? {
        if (size == 0) return null

        val result = ByteArray(size)
        val start = (head - size + buffer.size) % buffer.size
        val firstPart = minOf(size, buffer.size - start)
        System.arraycopy(buffer, start, result, 0, firstPart)
        System.arraycopy(buffer, 0, result, firstPart, size - firstPart)
        return result
    }

    @Synchronized
    fun clear() {
        head = 0
        size = 0
    }
}
