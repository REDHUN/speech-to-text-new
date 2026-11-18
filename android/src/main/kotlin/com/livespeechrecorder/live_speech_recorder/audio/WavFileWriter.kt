package com.livespeechrecorder.live_speech_recorder.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams PCM audio to a RIFF/WAVE file.
 *
 * The header is refreshed every few seconds of audio, so a killed process loses at most that much
 * from the declared length, and [repairHeader] can recover the rest.
 */
class WavFileWriter(
    val file: File,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16
) {
    private val byteRate = sampleRate * channels * (bitsPerSample / 8)
    private val headerRefreshBytes = byteRate.toLong() * HEADER_REFRESH_SECONDS
    private val lock = Any()

    private var randomAccessFile: RandomAccessFile? = null
    private var isWriting = false
    private var bytesAtLastHeader = 0L

    @Volatile
    private var totalAudioBytesWritten = 0L

    /**
     * Creates the file with a placeholder header, replacing any existing file at this path.
     */
    @Throws(IOException::class)
    fun open() {
        synchronized(lock) {
            if (isWriting) return
            file.parentFile?.mkdirs()
            if (file.exists() && !file.delete()) {
                throw IOException("Could not replace existing file: ${file.path}")
            }
            randomAccessFile = RandomAccessFile(file, "rw")
            totalAudioBytesWritten = 0
            bytesAtLastHeader = 0
            isWriting = true
            writeWavHeader(dataLength = 0)
        }
    }

    /**
     * Appends raw PCM audio bytes to the file.
     */
    @Throws(IOException::class)
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val raf = randomAccessFile ?: return
            if (!isWriting || length <= 0) return

            raf.write(buffer, offset, length)
            totalAudioBytesWritten += length

            if (headerRefreshBytes > 0 &&
                totalAudioBytesWritten - bytesAtLastHeader >= headerRefreshBytes
            ) {
                refreshHeader(raf)
            }
        }
    }

    /**
     * Updates the header without closing the file, so the file on disk is valid at this point.
     */
    @Throws(IOException::class)
    fun updateHeader() {
        synchronized(lock) {
            val raf = randomAccessFile ?: return
            if (isWriting) refreshHeader(raf)
        }
    }

    private fun refreshHeader(raf: RandomAccessFile) {
        val position = raf.filePointer
        writeWavHeader(totalAudioBytesWritten)
        raf.seek(position)
        bytesAtLastHeader = totalAudioBytesWritten
    }

    /**
     * Writes the final header and closes the file.
     *
     * @return Total audio duration in milliseconds.
     */
    @Throws(IOException::class)
    fun close(): Long {
        synchronized(lock) {
            val raf = randomAccessFile ?: return getDurationMs()
            try {
                writeWavHeader(totalAudioBytesWritten)
            } finally {
                randomAccessFile = null
                isWriting = false
                raf.close()
            }
            return getDurationMs()
        }
    }

    /**
     * Closes and deletes an in-progress file. A file that was already finalized is left alone.
     */
    fun cancel() {
        synchronized(lock) {
            val wasWriting = isWriting
            try {
                randomAccessFile?.close()
            } catch (ignored: IOException) {
            } finally {
                randomAccessFile = null
                isWriting = false
                if (wasWriting) file.delete()
            }
        }
    }

    fun getDurationMs(): Long {
        if (byteRate <= 0) return 0L
        return (totalAudioBytesWritten * 1000L) / byteRate
    }

    fun getBytesWritten(): Long = totalAudioBytesWritten

    private fun writeWavHeader(dataLength: Long) {
        val raf = randomAccessFile ?: return
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((dataLength + 36).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLength.toInt())

        raf.seek(0)
        raf.write(header.array())
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val HEADER_REFRESH_SECONDS = 5L

        /**
         * Makes a WAV header match the audio actually on disk, recovering a recording whose
         * process died before it was finalized.
         *
         * @return The audio duration in milliseconds, or null if [file] is not a readable WAV.
         */
        fun repairHeader(file: File): Long? {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    if (raf.length() < HEADER_BYTES) return null

                    val bytes = ByteArray(HEADER_BYTES)
                    raf.readFully(bytes)
                    val riff = String(bytes, 0, 4, Charsets.US_ASCII)
                    val wave = String(bytes, 8, 4, Charsets.US_ASCII)
                    if (riff != "RIFF" || wave != "WAVE") return null

                    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val rate = header.getInt(28)
                    val blockAlign = header.getShort(32).toInt().coerceAtLeast(1)
                    val available = raf.length() - HEADER_BYTES
                    val dataLength = available - (available % blockAlign)

                    if (header.getInt(40).toLong() and 0xFFFFFFFFL != dataLength) {
                        val patch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        raf.seek(4)
                        raf.write(patch.putInt(0, (dataLength + 36).toInt()).array())
                        raf.seek(40)
                        raf.write(patch.putInt(0, dataLength.toInt()).array())
                    }
                    return if (rate > 0) dataLength * 1000L / rate else 0L
                }
            } catch (e: IOException) {
                return null
            }
        }
    }
}
