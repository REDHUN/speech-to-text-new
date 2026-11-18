package com.livespeechrecorder.live_speech_recorder.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Consumer interface for raw PCM audio frames.
 */
fun interface PcmConsumer {
    fun onPcmAudio(buffer: ByteArray, readSize: Int)
}

/**
 * Distributes PCM from the single capture session to its consumers.
 *
 * Direct consumers run on the capture thread and must not block. Async consumers each get a
 * bounded queue that drops the oldest audio, so a slow recognizer cannot delay capture.
 */
class PcmAudioDistributor(
    private val bufferCapacity: Int = 64
) {
    private class AsyncEntry(
        val consumer: PcmConsumer,
        val channel: Channel<ByteArray>,
        val job: Job
    )

    private val directConsumers = CopyOnWriteArrayList<PcmConsumer>()
    private val asyncConsumers = CopyOnWriteArrayList<AsyncEntry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onAmplitudeUpdated: ((normalizedRms: Double, decibels: Double) -> Unit)? = null

    /** Called from the delivering thread when a consumer throws. */
    var onConsumerError: ((Exception) -> Unit)? = null

    fun addDirectConsumer(consumer: PcmConsumer) {
        directConsumers.addIfAbsent(consumer)
    }

    fun removeDirectConsumer(consumer: PcmConsumer) {
        directConsumers.remove(consumer)
    }

    fun addAsyncConsumer(consumer: PcmConsumer) {
        val channel = Channel<ByteArray>(
            capacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val job = scope.launch {
            for (audioChunk in channel) {
                try {
                    consumer.onPcmAudio(audioChunk, audioChunk.size)
                } catch (e: Exception) {
                    onConsumerError?.invoke(e)
                }
            }
        }
        asyncConsumers.add(AsyncEntry(consumer, channel, job))
    }

    /**
     * Stops feeding [consumer]. Audio already queued is still delivered, then [onDrained] runs on
     * a background thread.
     */
    fun removeAsyncConsumer(consumer: PcmConsumer, onDrained: (() -> Unit)? = null) {
        val entry = asyncConsumers.firstOrNull { it.consumer === consumer }
        if (entry == null) {
            onDrained?.invoke()
            return
        }
        asyncConsumers.remove(entry)
        entry.channel.close()
        if (onDrained != null) {
            entry.job.invokeOnCompletion { onDrained() }
        }
    }

    /**
     * Dispatches captured PCM audio to all consumers.
     */
    fun dispatch(buffer: ByteArray, readSize: Int) {
        if (readSize <= 0) return

        for (consumer in directConsumers) {
            try {
                consumer.onPcmAudio(buffer, readSize)
            } catch (e: Exception) {
                onConsumerError?.invoke(e)
            }
        }

        if (asyncConsumers.isNotEmpty()) {
            val copy = buffer.copyOf(readSize)
            for (entry in asyncConsumers) {
                entry.channel.trySend(copy)
            }
        }

        calculateAndEmitAmplitude(buffer, readSize)
    }

    /**
     * Computes RMS amplitude and dBFS from a 16-bit mono PCM buffer.
     */
    private fun calculateAndEmitAmplitude(buffer: ByteArray, readSize: Int) {
        val listener = onAmplitudeUpdated ?: return
        val evenSize = readSize and -2
        if (evenSize < 2) return

        var sumSquare = 0.0
        val sampleCount = evenSize / 2

        for (i in 0 until evenSize step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort().toInt()
            sumSquare += (shortSample * shortSample).toDouble()
        }

        val rms = sqrt(sumSquare / sampleCount)
        val normalized = min(1.0, max(0.0, rms / 32767.0))
        val db = if (rms > 0) 20 * log10(rms / 32767.0) else -100.0

        listener(normalized, db)
    }

    /**
     * Clears all consumers and terminates coroutines. The distributor cannot be used afterwards.
     */
    fun release() {
        directConsumers.clear()
        for (entry in asyncConsumers) {
            entry.channel.close()
        }
        asyncConsumers.clear()
        scope.cancel()
    }
}
