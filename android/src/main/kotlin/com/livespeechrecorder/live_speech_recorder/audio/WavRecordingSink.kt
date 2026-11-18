package com.livespeechrecorder.live_speech_recorder.audio

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Moves WAV disk writes off the capture thread.
 *
 * The queue holds about a minute of audio. Nothing is dropped silently: if the disk fails or
 * cannot keep up, [onFailure] is called once and the recording can be ended cleanly.
 */
class WavRecordingSink(
    private val writer: WavFileWriter,
    capacityChunks: Int = 600,
    private val onFailure: (code: String, message: String) -> Unit
) : PcmConsumer {

    private object FlushHeader
    private object Finish

    private val queue = ArrayBlockingQueue<Any>(capacityChunks)
    private val failed = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile private var closing = false

    @Throws(IOException::class)
    fun open() {
        writer.open()
        worker = Thread(::drainQueue, "LiveSpeechWavWriter").apply {
            isDaemon = true
            start()
        }
    }

    override fun onPcmAudio(buffer: ByteArray, readSize: Int) {
        if (closing || failed.get()) return
        if (!queue.offer(buffer.copyOf(readSize))) {
            reportFailure("DISK_TOO_SLOW", "Storage could not keep up with the recording.")
        }
    }

    /** Requests a valid on-disk header once queued audio has been written. */
    fun flushHeader() {
        queue.offer(FlushHeader)
    }

    /**
     * Writes all queued audio and finalizes the file. Blocking.
     *
     * @return The duration of the audio written, in milliseconds.
     */
    fun finish(): Long {
        stopWorker()
        return try {
            writer.close()
        } catch (e: IOException) {
            reportFailure("DISK_WRITE_FAILED", "Could not finalize the recording: ${e.message}")
            writer.getDurationMs()
        }
    }

    /** Stops writing and deletes the file. Blocking. */
    fun discard() {
        failed.set(true)
        queue.clear()
        stopWorker()
        writer.cancel()
    }

    private fun stopWorker() {
        closing = true
        val thread = worker ?: return
        queue.put(Finish)
        thread.join(WORKER_JOIN_MS)
        worker = null
    }

    private fun drainQueue() {
        while (true) {
            val item = queue.take()
            if (item === Finish) return
            if (failed.get()) continue
            try {
                when (item) {
                    is ByteArray -> writer.write(item, 0, item.size)
                    FlushHeader -> writer.updateHeader()
                }
            } catch (e: IOException) {
                reportFailure("DISK_WRITE_FAILED", "Could not write audio: ${e.message}")
            }
        }
    }

    private fun reportFailure(code: String, message: String) {
        if (failed.compareAndSet(false, true)) onFailure(code, message)
    }

    private companion object {
        const val WORKER_JOIN_MS = 10_000L
    }
}
