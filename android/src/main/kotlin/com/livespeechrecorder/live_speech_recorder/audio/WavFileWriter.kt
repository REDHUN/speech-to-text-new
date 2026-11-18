package com.livespeechrecorder.live_speech_recorder.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles streaming PCM audio directly to a standard RIFF/WAVE file on disk.
 *
 * Ensures that headers are written at the start and updated dynamically on pause/stop,
 * guaranteeing file integrity without loading the complete audio in memory.
 */
class WavFileWriter(
    val file: File,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16
) {
    private var randomAccessFile: RandomAccessFile? = null
    private var totalAudioBytesWritten: Long = 0
    private var isWriting: Boolean = false
    private val lock = Any()

    init {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
    }

    /**
     * Prepares the WAV file by writing a 44-byte placeholder header.
     */
    fun open() {
        synchronized(lock) {
            if (isWriting) return
            if (file.exists()) {
                file.delete()
            }
            randomAccessFile = RandomAccessFile(file, "rw")
            totalAudioBytesWritten = 0
            isWriting = true

            // Write 44-byte preliminary header with 0 data size
            writeWavHeader(dataLength = 0)
        }
    }

    /**
     * Appends raw PCM audio bytes to the file.
     */
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val raf = randomAccessFile ?: return
            if (!isWriting || length <= 0) return

            raf.write(buffer, offset, length)
            totalAudioBytesWritten += length
        }
    }

    /**
     * Flushes and updates the WAV header without closing the file.
     * Useful when pausing to ensure the file on disk remains valid.
     */
    fun updateHeader() {
        synchronized(lock) {
            val raf = randomAccessFile ?: return
            if (!isWriting) return

            val currentPos = raf.filePointer
            writeWavHeader(dataLength = totalAudioBytesWritten)
            raf.seek(currentPos)
        }
    }

    /**
     * Finalizes the WAV header with total file and data sizes, then closes the file.
     *
     * @return Total audio duration in milliseconds.
     */
    fun close(): Long {
        synchronized(lock) {
            val raf = randomAccessFile ?: return getDurationMs()
            try {
                writeWavHeader(dataLength = totalAudioBytesWritten)
                raf.close()
            } catch (e: Exception) {
                // Ignore close errors
            } finally {
                randomAccessFile = null
                isWriting = false
            }
            return getDurationMs()
        }
    }

    /**
     * Cancels recording and deletes the incomplete file.
     * Only deletes the file if it was actively being written to (never deletes a finalized/closed file).
     */
    fun cancel() {
        synchronized(lock) {
            val wasWriting = isWriting
            try {
                randomAccessFile?.close()
            } catch (ignored: Exception) {
            } finally {
                randomAccessFile = null
                isWriting = false
                if (wasWriting && file.exists()) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Calculates the audio duration in milliseconds based on audio bytes written.
     */
    fun getDurationMs(): Long {
        val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8)
        if (bytesPerSecond <= 0) return 0L
        return (totalAudioBytesWritten * 1000L) / bytesPerSecond
    }

    /**
     * Returns total audio bytes written so far.
     */
    fun getBytesWritten(): Long = totalAudioBytesWritten

    /**
     * Writes standard 44-byte RIFF WAV header at offset 0.
     */
    private fun writeWavHeader(dataLength: Long) {
        val raf = randomAccessFile ?: return
        val totalFileSize = dataLength + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put('R'.code.toByte())
        header.put('I'.code.toByte())
        header.put('F'.code.toByte())
        header.put('F'.code.toByte())
        header.putInt(totalFileSize.toInt())
        header.put('W'.code.toByte())
        header.put('A'.code.toByte())
        header.put('V'.code.toByte())
        header.put('E'.code.toByte())

        // "fmt " sub-chunk
        header.put('f'.code.toByte())
        header.put('m'.code.toByte())
        header.put('t'.code.toByte())
        header.put(' '.code.toByte())
        header.putInt(16) // Subchunk1Size (16 for PCM)
        header.putShort(1.toShort()) // AudioFormat (1 = PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())

        // "data" sub-chunk
        header.put('d'.code.toByte())
        header.put('a'.code.toByte())
        header.put('t'.code.toByte())
        header.put('a'.code.toByte())
        header.putInt(dataLength.toInt())

        raf.seek(0)
        raf.write(header.array())
    }
}
