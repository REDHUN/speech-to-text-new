package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.livespeechrecorder.live_speech_recorder.audio.AudioCaptureManager
import com.livespeechrecorder.live_speech_recorder.audio.CaptureState
import com.livespeechrecorder.live_speech_recorder.audio.PcmAudioDistributor
import com.livespeechrecorder.live_speech_recorder.audio.PcmConsumer
import com.livespeechrecorder.live_speech_recorder.audio.VoiceActivityDetector
import com.livespeechrecorder.live_speech_recorder.audio.WavFileWriter
import com.livespeechrecorder.live_speech_recorder.formatting.SentenceFormatter
import com.livespeechrecorder.live_speech_recorder.formatting.TimedSegment
import com.livespeechrecorder.live_speech_recorder.formatting.TranscriptAccumulator
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates the unified single-microphone capture, WAV recording, speech recognition session
 * lifecycle, automatic session recovery, deduplication, and streaming transcription dispatch.
 */
class RecognitionSessionManager(
    private val context: Context,
    private val config: RecognitionConfig = RecognitionConfig()
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val distributor = PcmAudioDistributor()
    private val vad = VoiceActivityDetector(sampleRate = config.sampleRate)
    private val sentenceFormatter = SentenceFormatter(
        enablePunctuation = config.enablePunctuation,
        enableCapitalization = true
    )
    val accumulator = TranscriptAccumulator(sentenceFormatter)

    private var audioCaptureManager: AudioCaptureManager? = null
    private var wavWriter: WavFileWriter? = null
    private var wavConsumer: PcmConsumer? = null
    private var recognitionConsumer: PcmConsumer? = null
    private var recognitionBackend: SpeechRecognitionBackend? = null

    private val isRecordingActive = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val sessionCounter = AtomicInteger(0)

    private var recordingStartTimeMs: Long = 0L
    private var pauseStartTimeMs: Long = 0L
    private var totalPausedDurationMs: Long = 0L

    // Pre-roll PCM buffer (stores last ~300ms) to prevent clipping first syllable on session restart
    private val preRollLock = Any()
    private val preRollBytes = (config.sampleRate * 2 * 300) / 1000 // 300ms
    private val preRollBuffer = ByteArray(preRollBytes)
    private var preRollHead = 0
    private var preRollSize = 0

    // Callback listeners
    var onTranscriptionUpdate: ((fullText: String, partialText: String, confirmedText: String, isFinal: Boolean, segments: List<TimedSegment>) -> Unit)? = null
    var onAmplitudeUpdate: ((normalized: Double, decibels: Double) -> Unit)? = null
    var onDurationUpdate: ((durationMs: Long) -> Unit)? = null
    var onStateChanged: ((state: String) -> Unit)? = null
    var onError: ((code: String, message: String) -> Unit)? = null

    init {
        // Register VAD to analyze PCM audio
        distributor.addDirectConsumer(vad)

        // Setup amplitude callback
        distributor.onAmplitudeUpdated = { norm, db ->
            mainHandler.post {
                onAmplitudeUpdate?.invoke(norm, db)
            }
        }

        // Setup VAD listener to support pause-aware formatting
        vad.listener = object : VoiceActivityDetector.VadListener {
            override fun onSpeechStart() {}
            override fun onSpeechEnd(silenceDurationMs: Long) {
                // If user paused speaking for > 1500ms, finalize any existing partial result
                if (isRecordingActive.get() && silenceDurationMs >= 1500L) {
                    val partial = accumulator.getCurrentPartial()
                    if (partial.isNotBlank()) {
                        val currentAudioTime = getActiveDurationMs()
                        accumulator.confirmSegment(partial, currentAudioTime)
                        dispatchTranscription(isFinal = false)
                    }
                }
            }
            override fun onPauseDetected(durationMs: Long) {}
        }
    }

    /**
     * Starts single-microphone recording and speech recognition session.
     */
    fun start(outputFile: File): Boolean {
        if (isRecordingActive.get()) return false

        accumulator.reset()
        recordingStartTimeMs = System.currentTimeMillis()
        totalPausedDurationMs = 0L
        isRecordingActive.set(true)
        isPaused.set(false)

        // 1. Prepare WAV file writer
        val writer = WavFileWriter(file = outputFile, sampleRate = config.sampleRate)
        writer.open()
        wavWriter = writer
        val directConsumer = PcmConsumer { buffer, readSize ->
            if (!isPaused.get()) {
                writer.write(buffer, 0, readSize)
            }
        }
        wavConsumer = directConsumer
        distributor.addDirectConsumer(directConsumer)

        // 2. Select and initialize recognition backend
        val capability = RecognitionCapabilityDetector.detect(context)
        val backend: SpeechRecognitionBackend = if (capability.supportsAudioSourceInjection && capability.hasSpeechRecognizer) {
            AndroidSpeechRecognizerBackend()
        } else {
            FallbackStreamingBackend()
        }
        recognitionBackend = backend

        backend.initialize(context, config, object : BackendListener {
            override fun onPartialResult(text: String) {
                if (!isRecordingActive.get()) return
                accumulator.updatePartial(text)
                dispatchTranscription(isFinal = false)
            }

            override fun onSegmentResult(text: String) {
                if (!isRecordingActive.get()) return
                val currentAudioTime = getActiveDurationMs()
                accumulator.confirmSegment(text, currentAudioTime)
                dispatchTranscription(isFinal = false)
            }

            override fun onSessionCompleted(finalText: String?) {
                if (!isRecordingActive.get()) return

                if (!finalText.isNullOrBlank()) {
                    val currentAudioTime = getActiveDurationMs()
                    accumulator.confirmSegment(finalText, currentAudioTime)
                    dispatchTranscription(isFinal = false)
                }

                // If recording is still active, automatically restart recognition session for long-form speech
                restartRecognitionSession()
            }

            override fun onError(code: String, message: String, isRecoverable: Boolean) {
                if (!isRecordingActive.get()) return

                // Non-fatal error: dispatch error notice to UI but DO NOT abort audio recording
                mainHandler.post {
                    onError?.invoke(code, message)
                }

                // If recoverable (e.g. SpeechRecognizer silence timeout), automatically restart session
                if (isRecoverable) {
                    restartRecognitionSession()
                }
            }
        })

        // 3. Register audio pipe consumer for recognition
        val asyncConsumer = PcmConsumer { buffer, readSize ->
            if (!isPaused.get()) {
                // Buffer into pre-roll ring buffer
                appendPreRoll(buffer, readSize)
                // Write into active recognition backend
                recognitionBackend?.writePcm(buffer, 0, readSize)
            }
        }
        recognitionConsumer = asyncConsumer
        distributor.addAsyncConsumer(asyncConsumer)

        // 4. Initialize single AudioRecord capture
        val captureManager = AudioCaptureManager(
            sampleRate = config.sampleRate,
            distributor = distributor
        )
        audioCaptureManager = captureManager

        captureManager.onError = { code, msg ->
            mainHandler.post { onError?.invoke(code, msg) }
        }

        captureManager.startCapture()

        // 5. Start initial recognition session
        startBackendSession()

        // 6. Start duration tracking loop
        startDurationTracker()

        mainHandler.post { onStateChanged?.invoke("RECORDING") }
        return true
    }

    private fun startBackendSession() {
        val currentSessionId = UUID.randomUUID().toString()
        sessionCounter.incrementAndGet()
        accumulator.setSessionStartTime(getActiveDurationMs())

        recognitionBackend?.startSession(currentSessionId)

        // Inject pre-roll audio into new session so initial sounds aren't truncated
        injectPreRollAudio()
    }

    /**
     * Seamlessly restarts speech recognition session when Android SpeechRecognizer terminates
     * without interrupting audio recording in WavFileWriter.
     */
    private fun restartRecognitionSession() {
        if (!isRecordingActive.get() || isPaused.get()) return

        mainHandler.postDelayed({
            if (isRecordingActive.get() && !isPaused.get()) {
                startBackendSession()
            }
        }, 150)
    }

    fun pause() {
        if (!isRecordingActive.get() || isPaused.get()) return

        isPaused.set(true)
        pauseStartTimeMs = System.currentTimeMillis()

        audioCaptureManager?.pause()
        wavWriter?.updateHeader()
        recognitionBackend?.stopSession()

        mainHandler.post { onStateChanged?.invoke("PAUSED") }
    }

    fun resume() {
        if (!isRecordingActive.get() || !isPaused.get()) return

        val pauseDuration = System.currentTimeMillis() - pauseStartTimeMs
        if (pauseDuration > 0) {
            totalPausedDurationMs += pauseDuration
        }

        isPaused.set(false)
        audioCaptureManager?.resume()
        startBackendSession()

        mainHandler.post { onStateChanged?.invoke("RECORDING") }
    }

    /**
     * Stops recording, finalizes WAV header, and flushes pending recognition text.
     */
    fun stop(): Pair<Long, String> {
        if (!isRecordingActive.get()) {
            val dur = wavWriter?.getDurationMs() ?: 0L
            val txt = accumulator.getFormattedFullText()
            return Pair(dur, txt)
        }

        isRecordingActive.set(false)
        isPaused.set(false)

        // Finalize remaining partial text into confirmed
        val remainingPartial = accumulator.getCurrentPartial()
        if (remainingPartial.isNotBlank()) {
            accumulator.confirmSegment(remainingPartial, getActiveDurationMs())
        }

        // Stop audio capture first
        audioCaptureManager?.stop()
        audioCaptureManager = null

        // Stop recognition backend
        recognitionBackend?.stopSession()
        recognitionBackend?.release()
        recognitionBackend = null

        // Detach consumers from distributor
        wavConsumer?.let {
            distributor.removeDirectConsumer(it)
            wavConsumer = null
        }
        recognitionConsumer?.let {
            distributor.removeAsyncConsumer(it)
            recognitionConsumer = null
        }

        // Finalize WAV file
        val durationMs = wavWriter?.close() ?: 0L
        wavWriter = null

        // Final transcript
        val finalTranscript = accumulator.getFormattedFullText()
        dispatchTranscription(isFinal = true)

        mainHandler.post { onStateChanged?.invoke("STOPPED") }
        return Pair(durationMs, finalTranscript)
    }

    fun cancel() {
        isRecordingActive.set(false)
        isPaused.set(false)

        audioCaptureManager?.stop()
        audioCaptureManager = null

        recognitionBackend?.stopSession()
        recognitionBackend?.release()
        recognitionBackend = null

        wavConsumer?.let {
            distributor.removeDirectConsumer(it)
            wavConsumer = null
        }
        recognitionConsumer?.let {
            distributor.removeAsyncConsumer(it)
            recognitionConsumer = null
        }

        wavWriter?.cancel()
        wavWriter = null

        distributor.release()
        accumulator.reset()

        mainHandler.post { onStateChanged?.invoke("IDLE") }
    }

    private fun getActiveDurationMs(): Long {
        if (recordingStartTimeMs == 0L) return 0L
        val now = if (isPaused.get()) pauseStartTimeMs else System.currentTimeMillis()
        val raw = now - recordingStartTimeMs - totalPausedDurationMs
        return if (raw > 0L) raw else 0L
    }

    private fun dispatchTranscription(isFinal: Boolean) {
        val silenceMs = vad.getCurrentSilenceDurationMs()
        val fullText = accumulator.getFormattedFullText(silenceMs)
        val partialText = accumulator.getCurrentPartial()
        val confirmedText = accumulator.getConfirmedText()
        val segments = accumulator.getSegments()

        mainHandler.post {
            onTranscriptionUpdate?.invoke(fullText, partialText, confirmedText, isFinal, segments)
        }
    }

    private fun startDurationTracker() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isRecordingActive.get()) {
                    val duration = getActiveDurationMs()
                    onDurationUpdate?.invoke(duration)
                    mainHandler.postDelayed(this, 250)
                }
            }
        })
    }

    private fun appendPreRoll(buffer: ByteArray, length: Int) {
        synchronized(preRollLock) {
            val toCopy = minOf(length, preRollBytes)
            val srcOffset = length - toCopy
            for (i in 0 until toCopy) {
                preRollBuffer[(preRollHead + i) % preRollBytes] = buffer[srcOffset + i]
            }
            preRollHead = (preRollHead + toCopy) % preRollBytes
            preRollSize = minOf(preRollBytes, preRollSize + toCopy)
        }
    }

    private fun injectPreRollAudio() {
        synchronized(preRollLock) {
            if (preRollSize <= 0) return
            val linear = ByteArray(preRollSize)
            val startIdx = (preRollHead - preRollSize + preRollBytes) % preRollBytes
            for (i in 0 until preRollSize) {
                linear[i] = preRollBuffer[(startIdx + i) % preRollBytes]
            }
            recognitionBackend?.writePcm(linear, 0, preRollSize)
        }
    }
}
