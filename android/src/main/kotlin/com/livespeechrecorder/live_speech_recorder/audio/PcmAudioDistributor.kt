package com.livespeechrecorder.live_speech_recorder.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Thread-safe, bounded, non-blocking audio distributor.
 *
 * Distributes raw PCM audio from a single AudioRecord capture session to multiple consumers:
 * 1. Audio recording pipeline (WavFileWriter) - prioritised synchronous or dedicated consumer.
 * 2. Speech recognition pipeline (SpeechRecognizer / on-device ASR) - decoupled through bounded channels.
 * 3. Amplitude / VAD analyzers.
 *
 * Ensures slow speech recognition processing NEVER blocks or drops audio in the WAV recording pipeline.
 */
class PcmAudioDistributor(
    private val bufferCapacity: Int = 64
) {
    private val directConsumers = CopyOnWriteArrayList<PcmConsumer>()
    private val asyncConsumers = CopyOnWriteArrayList<Pair<PcmConsumer, Channel<ByteArray>>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onAmplitudeUpdated: ((normalizedRms: Double, decibels: Double) -> Unit)? = null

    /**
     * Registers a consumer to receive audio directly on the capture thread.
     * Use this for lightweight, non-blocking consumers like WavFileWriter.
     */
    fun addDirectConsumer(consumer: PcmConsumer) {
        if (!directConsumers.contains(consumer)) {
            directConsumers.add(consumer)
        }
    }

    fun removeDirectConsumer(consumer: PcmConsumer) {
        directConsumers.remove(consumer)
    }

    /**
     * Registers a consumer to receive audio asynchronously through a bounded non-blocking channel.
     * If the consumer falls behind, oldest frames can be dropped or handled safely to prevent OOM
     * and guarantee that audio recording continues unaffected.
     */
    fun addAsyncConsumer(consumer: PcmConsumer) {
        val channel = Channel<ByteArray>(
            capacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val pair = Pair(consumer, channel)
        asyncConsumers.add(pair)

        scope.launch {
            for (audioChunk in channel) {
                try {
                    consumer.onPcmAudio(audioChunk, audioChunk.size)
                } catch (e: Exception) {
                    // Prevent consumer exceptions from breaking the dispatch loop
                }
            }
        }
    }

    fun removeAsyncConsumer(consumer: PcmConsumer) {
        val iterator = asyncConsumers.iterator()
        while (iterator.hasNext()) {
            val pair = iterator.next()
            if (pair.first == consumer) {
                pair.second.close()
                asyncConsumers.remove(pair)
            }
        }
    }

    /**
     * Dispatches captured PCM audio to all direct and asynchronous consumers.
     */
    fun dispatch(buffer: ByteArray, readSize: Int) {
        if (readSize <= 0) return

        // 1. Dispatch to direct consumers (e.g. WAV recorder)
        for (consumer in directConsumers) {
            try {
                consumer.onPcmAudio(buffer, readSize)
            } catch (e: Exception) {
                // Keep processing
            }
        }

        // 2. Dispatch copies to async consumers (e.g. Speech recognition pipe)
        if (asyncConsumers.isNotEmpty()) {
            val copy = buffer.copyOf(readSize)
            for (pair in asyncConsumers) {
                pair.second.trySend(copy)
            }
        }

        // 3. Compute amplitude metrics
        calculateAndEmitAmplitude(buffer, readSize)
    }

    /**
     * Computes RMS amplitude and dBFS from 16-bit Mono PCM buffer.
     */
    private fun calculateAndEmitAmplitude(buffer: ByteArray, readSize: Int) {
        val listener = onAmplitudeUpdated ?: return
        if (readSize < 2) return

        var sumSquare = 0.0
        val sampleCount = readSize / 2

        for (i in 0 until readSize step 2) {
            // 16-bit little-endian sample
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sumSquare += (shortSample * shortSample).toDouble()
        }

        val rms = sqrt(sumSquare / sampleCount)
        // Normalized 0.0 to 1.0 (Short.MAX_VALUE = 32767)
        val normalized = min(1.0, max(0.0, rms / 32767.0))

        // Decibels relative to full scale (dBFS): range typically -90dB to 0dB
        val db = if (rms > 0) 20 * log10(rms / 32767.0) else -100.0

        listener(normalized, db)
    }

    /**
     * Clears all consumers and terminates coroutines.
     */
    fun release() {
        directConsumers.clear()
        for (pair in asyncConsumers) {
            pair.second.close()
        }
        asyncConsumers.clear()
        scope.cancel()
    }
}
