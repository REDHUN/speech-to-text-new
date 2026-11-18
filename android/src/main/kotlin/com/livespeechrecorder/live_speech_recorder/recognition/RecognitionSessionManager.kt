package com.livespeechrecorder.live_speech_recorder.recognition

import com.livespeechrecorder.live_speech_recorder.audio.CaptureListener
import com.livespeechrecorder.live_speech_recorder.audio.PcmAudioDistributor
import com.livespeechrecorder.live_speech_recorder.audio.PcmCaptureSource
import com.livespeechrecorder.live_speech_recorder.audio.PcmConsumer
import com.livespeechrecorder.live_speech_recorder.audio.PcmRingBuffer
import com.livespeechrecorder.live_speech_recorder.audio.VoiceActivityDetector
import com.livespeechrecorder.live_speech_recorder.audio.WavFileWriter
import com.livespeechrecorder.live_speech_recorder.audio.WavRecordingSink
import com.livespeechrecorder.live_speech_recorder.formatting.SentenceFormatter
import com.livespeechrecorder.live_speech_recorder.formatting.TranscriptAccumulator
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

data class RecordingSummary(
    val audioPath: String,
    val transcription: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val sampleRate: Int,
    val channels: Int,
    /** The error that ended the recording early, or null if it was stopped normally. */
    val failureCode: String?
)

data class StartOutcome(
    val audioPath: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

data class TranscriptUpdate(
    val partialText: String,
    val formattedPartial: String,
    val isFinal: Boolean,
    /** True when earlier segments no longer apply because a new recording began. */
    val isReset: Boolean
)

/**
 * The state machine for one configured recorder.
 *
 * All public methods and callbacks run on the main thread. Blocking work runs on the platform's
 * background thread, during which the session is in a transitional state. Commands arriving in
 * that window are queued and run in order, so rapid button presses cannot overlap.
 */
class RecognitionSessionManager(
    private val platform: SessionPlatform,
    private val config: RecognitionConfig = RecognitionConfig()
) {
    private enum class State(val wireName: String?, val isTransitional: Boolean = false) {
        IDLE("IDLE"),
        STARTING(null, true),
        RECORDING("RECORDING"),
        PAUSING(null, true),
        PAUSED("PAUSED"),
        RESUMING(null, true),
        STOPPING(null, true),
        CANCELLING(null, true),
        STOPPED("STOPPED"),
        FAILED("ERROR")
    }

    private val bytesPerSecond = config.sampleRate * BYTES_PER_SAMPLE
    private val distributor = PcmAudioDistributor()
    private val vad = VoiceActivityDetector(sampleRate = config.sampleRate)
    private val accumulator = TranscriptAccumulator(
        SentenceFormatter(
            enablePunctuation = config.enablePunctuation,
            enableCapitalization = true,
            language = config.language
        )
    )
    private val replayBuffer = PcmRingBuffer(bytesPerSecond)
    private val capturedBytes = AtomicLong(0)
    private val pendingCommands = ArrayDeque<() -> Unit>()
    private val stopCallbacks = mutableListOf<(RecordingSummary?) -> Unit>()

    private val recognitionConsumer = PcmConsumer { buffer, size ->
        replayBuffer.append(buffer, size)
        backend?.writePcm(buffer, 0, size)
    }

    private var state = State.IDLE
    private var capture: PcmCaptureSource? = null
    @Volatile private var backend: SpeechRecognitionBackend? = null
    private var sink: WavRecordingSink? = null
    private var wavConsumer: PcmConsumer? = null
    private var outputFile: File? = null
    private var lastSummary: RecordingSummary? = null
    private var failureCode: String? = null
    private var isFeedingRecognizer = false
    private var replayHasConsumedAudio = false
    private var recognitionGeneration = 0
    private var restartFailures = 0
    private var lastReportedFailureCode: String? = null
    private var isFinalizing = false
    private var released = false

    private var cancelRestart: (() -> Unit)? = null
    private var cancelDurationTick: (() -> Unit)? = null
    private var cancelFinalizeTimeout: (() -> Unit)? = null

    var onTranscriptionUpdate: ((TranscriptUpdate) -> Unit)? = null
    var onAmplitudeUpdate: ((normalized: Double) -> Unit)? = null
    var onDurationUpdate: ((durationMs: Long) -> Unit)? = null
    var onStateChanged: ((state: String) -> Unit)? = null
    var onError: ((code: String, message: String, isFatal: Boolean) -> Unit)? = null

    /** True from the start of a recording until its file has been finalized or discarded. */
    val isActive: Boolean
        get() = state != State.IDLE && state != State.STOPPED && state != State.FAILED

    val activeAudioPath: String?
        get() = if (isActive) outputFile?.absolutePath else null

    val lastAudioPath: String?
        get() = lastSummary?.audioPath ?: outputFile?.absolutePath

    val wireState: String
        get() = state.wireName ?: when (state) {
            State.PAUSING, State.RESUMING -> "PAUSED"
            State.STOPPING -> "STOPPED"
            else -> "IDLE"
        }

    init {
        distributor.addDirectConsumer(vad)
        distributor.onAmplitudeUpdated = { normalized, _ ->
            platform.runOnMain { onAmplitudeUpdate?.invoke(normalized) }
        }
        distributor.onConsumerError = { error ->
            platform.runOnMain { reportWarning("AUDIO_PIPELINE_ERROR", "Audio processing error: ${error.message}") }
        }
        vad.listener = object : VoiceActivityDetector.VadListener {
            override fun onSpeechStart(precedingSilenceMs: Long) = accumulator.notePause(precedingSilenceMs)
            override fun onSpeechEnd() {}
        }
    }

    /** Begins recording to [file]. [onResult] runs once the microphone is open or has failed. */
    fun start(file: File, onResult: (StartOutcome) -> Unit) = whenSettled {
        when {
            released -> onResult(StartOutcome(errorCode = "NOT_INITIALIZED", errorMessage = "The recorder was released."))
            isActive -> onResult(StartOutcome(errorCode = "ALREADY_RECORDING", errorMessage = "A recording is already in progress."))
            !platform.hasRecordPermission() ->
                onResult(StartOutcome(errorCode = "PERMISSION_DENIED", errorMessage = "RECORD_AUDIO permission has not been granted."))
            else -> beginStart(file, onResult)
        }
    }

    private fun beginStart(file: File, onResult: (StartOutcome) -> Unit) {
        state = State.STARTING
        outputFile = file
        lastSummary = null
        failureCode = null
        lastReportedFailureCode = null
        restartFailures = 0
        isFinalizing = false
        capturedBytes.set(0)
        accumulator.reset()
        clearReplayBuffer()

        if (config.enableBackgroundRecording &&
            !platform.startForegroundNotice(config.notificationTitle, config.notificationText)
        ) {
            reportWarning("BACKGROUND_RECORDING_UNAVAILABLE", "Recording may be silenced if the app leaves the foreground.")
        }

        val newSink = WavRecordingSink(WavFileWriter(file, config.sampleRate)) { code, message ->
            platform.runOnMain { failRecording(code, message) }
        }
        val consumer = PcmConsumer { buffer, size ->
            newSink.onPcmAudio(buffer, size)
            capturedBytes.addAndGet(size.toLong())
        }
        val newCapture = platform.createCapture(config.sampleRate, distributor)
        newCapture.listener = object : CaptureListener {
            override fun onCaptureFatal(code: String, message: String) {
                platform.runOnMain { failRecording(code, message) }
            }

            override fun onCaptureWarning(code: String, message: String) {
                platform.runOnMain { reportWarning(code, message) }
            }
        }

        sink = newSink
        wavConsumer = consumer
        capture = newCapture
        distributor.addDirectConsumer(consumer)
        attachBackend()
        startFeedingRecognizer()

        platform.runInBackground {
            vad.reset()
            // The microphone opens first, so a failed start never replaces an existing file.
            // Audio captured meanwhile waits in the sink's queue.
            val result = newCapture.start()
            val outcome = if (!result.started) {
                StartOutcome(errorCode = result.errorCode, errorMessage = result.errorMessage)
            } else {
                try {
                    newSink.open()
                    null
                } catch (e: IOException) {
                    newCapture.stop()
                    StartOutcome(errorCode = "FILE_OPEN_FAILED", errorMessage = "Could not create the audio file: ${e.message}")
                }
            }
            if (outcome != null) newSink.discard()
            platform.runOnMain { completeStart(file, outcome, onResult) }
        }
    }

    private fun completeStart(file: File, failure: StartOutcome?, onResult: (StartOutcome) -> Unit) {
        if (failure != null) {
            detachAudio()
            releaseBackend()
            platform.stopForegroundNotice()
            capture = null
            sink = null
            outputFile = null
            setState(State.IDLE)
            onResult(failure)
        } else {
            setState(State.RECORDING)
            dispatchTranscription(isFinal = false, isReset = true)
            startRecognitionSession()
            scheduleDurationTick()
            onResult(StartOutcome(audioPath = file.absolutePath))
        }
        runPendingCommands()
    }

    fun pause(onResult: (Boolean) -> Unit) = whenSettled {
        if (state != State.RECORDING) {
            onResult(false)
            return@whenSettled
        }
        state = State.PAUSING
        cancelTimers()
        val activeCapture = capture
        val activeSink = sink
        platform.runInBackground {
            activeCapture?.stop()
            activeSink?.flushHeader()
            platform.runOnMain {
                setState(State.PAUSED)
                emitDuration()
                clearReplayBuffer()
                finishRecognitionAfterDrain { finished -> if (!finished) promotePartial() }
                onResult(true)
                runPendingCommands()
            }
        }
    }

    fun resume(onResult: (Boolean) -> Unit) = whenSettled {
        if (state != State.PAUSED) {
            onResult(false)
            return@whenSettled
        }
        state = State.RESUMING
        startFeedingRecognizer()
        val activeCapture = capture
        platform.runInBackground {
            val result = activeCapture?.start()
            platform.runOnMain {
                if (result?.started == true) {
                    setState(State.RECORDING)
                    startRecognitionSession()
                    scheduleDurationTick()
                    onResult(true)
                } else {
                    state = State.PAUSED
                    onResult(false)
                    failRecording(
                        result?.errorCode ?: "CAPTURE_FAILED",
                        result?.errorMessage ?: "The microphone could not be reopened."
                    )
                }
                runPendingCommands()
            }
        }
    }

    /**
     * Stops recording, keeps the file, and waits briefly for final recognition results.
     *
     * Repeated calls return the same result. [onResult] receives null if nothing was recorded.
     */
    fun stop(onResult: (RecordingSummary?) -> Unit) = whenSettled {
        when (state) {
            State.RECORDING, State.PAUSED -> {
                stopCallbacks.add(onResult)
                beginStop()
            }
            else -> onResult(lastSummary)
        }
    }

    private fun beginStop() {
        state = State.STOPPING
        cancelTimers()
        cancelFinalizeTimeout = platform.runOnMainDelayed(FINAL_RESULT_TIMEOUT_MS) { finalizeStop() }
        val activeCapture = capture
        platform.runInBackground {
            activeCapture?.stop()
            platform.runOnMain {
                finishRecognitionAfterDrain { expectsResult ->
                    if (!expectsResult) finalizeStop()
                }
            }
        }
    }

    private fun finalizeStop() {
        if (state != State.STOPPING || isFinalizing) return
        isFinalizing = true
        cancelFinalizeTimeout?.invoke()
        cancelFinalizeTimeout = null

        promotePartial()
        releaseBackend()
        detachAudio()

        val file = outputFile
        val activeSink = sink
        capture = null
        sink = null
        platform.runInBackground {
            val durationMs = activeSink?.finish() ?: 0L
            val sizeBytes = file?.length() ?: 0L
            platform.runOnMain { completeStop(file, durationMs, sizeBytes) }
        }
    }

    private fun completeStop(file: File?, durationMs: Long, sizeBytes: Long) {
        val summary = RecordingSummary(
            audioPath = file?.absolutePath.orEmpty(),
            transcription = accumulator.getConfirmedText(),
            durationMs = durationMs,
            fileSizeBytes = sizeBytes,
            sampleRate = config.sampleRate,
            channels = 1,
            failureCode = failureCode
        )
        lastSummary = summary
        platform.stopForegroundNotice()
        dispatchTranscription(isFinal = true)
        onDurationUpdate?.invoke(durationMs)
        setState(if (failureCode == null) State.STOPPED else State.FAILED)

        val callbacks = stopCallbacks.toList()
        stopCallbacks.clear()
        callbacks.forEach { it(summary) }
        runPendingCommands()
    }

    /** Discards an in-progress recording and its file. Finished recordings are left untouched. */
    fun cancel(onResult: (Boolean) -> Unit) = whenSettled {
        if (state != State.RECORDING && state != State.PAUSED) {
            lastSummary = null
            outputFile = null
            accumulator.reset()
            setState(State.IDLE)
            onResult(false)
            return@whenSettled
        }

        state = State.CANCELLING
        cancelTimers()
        val activeCapture = capture
        val activeSink = sink
        releaseBackend()
        platform.runInBackground {
            activeCapture?.stop()
            activeSink?.discard()
            platform.runOnMain {
                detachAudio()
                capture = null
                sink = null
                outputFile = null
                lastSummary = null
                accumulator.reset()
                platform.stopForegroundNotice()
                dispatchTranscription(isFinal = false, isReset = true)
                setState(State.IDLE)
                onResult(true)
                runPendingCommands()
            }
        }
    }

    /** Finalizes any active recording, keeping its file, then frees all resources. */
    fun release(onComplete: () -> Unit) {
        stop {
            whenSettled {
                if (!released) {
                    released = true
                    releaseBackend()
                    distributor.release()
                }
                onComplete()
            }
        }
    }

    private fun attachBackend() {
        val newBackend = platform.createBackend(config)
        if (newBackend == null) {
            reportWarning("RECOGNIZER_UNAVAILABLE", "This device has no speech recognition service. Audio is still being recorded.")
            return
        }
        newBackend.initialize(backendListener)
        backend = newBackend
    }

    private fun releaseBackend() {
        recognitionGeneration++
        backend?.release()
        backend = null
    }

    private fun startFeedingRecognizer() {
        if (backend != null && !isFeedingRecognizer) {
            isFeedingRecognizer = true
            distributor.addAsyncConsumer(recognitionConsumer)
        }
    }

    private fun startRecognitionSession() {
        val activeBackend = backend ?: return
        recognitionGeneration++
        promotePartial()
        accumulator.setSessionStartTime(positionMs())

        val replaysConsumedAudio = replayHasConsumedAudio
        replayHasConsumedAudio = true
        activeBackend.startSession {
            val replay = replayBuffer.snapshot()
            if (replay != null && replaysConsumedAudio) accumulator.beginOverlapWindow()
            replay
        }
    }

    /** Lets queued audio reach the recognizer, then ends its session. */
    private fun finishRecognitionAfterDrain(onFinished: (expectsResult: Boolean) -> Unit) {
        val generation = ++recognitionGeneration
        val finish: () -> Unit = {
            if (generation == recognitionGeneration) {
                onFinished(backend?.finishSession() == true)
            }
        }
        if (isFeedingRecognizer) {
            isFeedingRecognizer = false
            distributor.removeAsyncConsumer(recognitionConsumer) { platform.runOnMain(finish) }
        } else {
            finish()
        }
    }

    private fun detachAudio() {
        wavConsumer?.let { distributor.removeDirectConsumer(it) }
        wavConsumer = null
        if (isFeedingRecognizer) {
            isFeedingRecognizer = false
            distributor.removeAsyncConsumer(recognitionConsumer)
        }
    }

    private val backendListener = object : BackendListener {
        override fun onPartialResult(text: String) {
            if (state != State.RECORDING) return
            markRecognitionHealthy()
            accumulator.updatePartial(text)
            dispatchTranscription(isFinal = false)
        }

        override fun onSegmentResult(text: String, confidence: Double?) {
            if (!acceptsResults()) return
            markRecognitionHealthy()
            accumulator.confirmSegment(text, positionMs(), confidence)
            dispatchTranscription(isFinal = false)
        }

        override fun onSessionCompleted(finalText: String?, confidence: Double?) {
            if (!acceptsResults()) return
            if (!finalText.isNullOrBlank()) {
                markRecognitionHealthy()
                accumulator.confirmSegment(finalText, positionMs(), confidence)
            } else {
                promotePartial()
            }
            dispatchTranscription(isFinal = false)

            when (state) {
                State.STOPPING -> finalizeStop()
                State.RECORDING -> scheduleRestart(afterFailure = false)
                else -> Unit
            }
        }

        override fun onError(failure: BackendFailure) {
            if (!acceptsResults()) return
            promotePartial()
            dispatchTranscription(isFinal = false)

            if (state == State.STOPPING) {
                finalizeStop()
                return
            }
            if (!failure.isRoutine && failure.code != lastReportedFailureCode) {
                lastReportedFailureCode = failure.code
                onError?.invoke(failure.code, failure.message, false)
            }
            if (failure.isRecoverable && state == State.RECORDING) {
                scheduleRestart(afterFailure = !failure.isRoutine)
            }
        }
    }

    private fun acceptsResults() =
        state == State.RECORDING || state == State.PAUSING || state == State.PAUSED ||
            state == State.RESUMING || state == State.STOPPING

    private fun markRecognitionHealthy() {
        restartFailures = 0
        lastReportedFailureCode = null
    }

    private fun scheduleRestart(afterFailure: Boolean) {
        if (afterFailure) restartFailures++
        val delayMs = if (afterFailure) {
            minOf(RESTART_DELAY_MS shl minOf(restartFailures, MAX_BACKOFF_SHIFT), MAX_RESTART_DELAY_MS)
        } else {
            RESTART_DELAY_MS
        }
        cancelRestart?.invoke()
        cancelRestart = platform.runOnMainDelayed(delayMs) {
            cancelRestart = null
            if (state == State.RECORDING) startRecognitionSession()
        }
    }

    private fun promotePartial() {
        val partial = accumulator.getCurrentPartial()
        if (partial.isNotBlank()) accumulator.confirmSegment(partial, positionMs())
    }

    private fun clearReplayBuffer() {
        replayBuffer.clear()
        replayHasConsumedAudio = false
    }

    /** Ends the recording because audio can no longer be captured or stored. The file is kept. */
    private fun failRecording(code: String, message: String) {
        if (failureCode != null || state == State.IDLE || state == State.STOPPED || state == State.FAILED) return
        failureCode = code
        onError?.invoke(code, message, true)
        whenSettled {
            if (state == State.RECORDING || state == State.PAUSED) beginStop()
        }
    }

    private fun reportWarning(code: String, message: String) {
        onError?.invoke(code, message, false)
    }

    private fun whenSettled(command: () -> Unit) {
        if (state.isTransitional) pendingCommands.addLast(command) else command()
    }

    private fun runPendingCommands() {
        while (!state.isTransitional) {
            val command = pendingCommands.removeFirstOrNull() ?: return
            command()
        }
    }

    private fun setState(newState: State) {
        state = newState
        onStateChanged?.invoke(wireState)
    }

    private fun cancelTimers() {
        cancelRestart?.invoke()
        cancelRestart = null
        cancelDurationTick?.invoke()
        cancelDurationTick = null
    }

    private fun scheduleDurationTick() {
        cancelDurationTick?.invoke()
        emitDuration()
        cancelDurationTick = platform.runOnMainDelayed(DURATION_INTERVAL_MS) {
            cancelDurationTick = null
            if (state == State.RECORDING) scheduleDurationTick()
        }
    }

    private fun emitDuration() {
        onDurationUpdate?.invoke(positionMs())
    }

    /** The position in the recorded audio, which matches the WAV and excludes pauses. */
    private fun positionMs(): Long = capturedBytes.get() * 1000L / bytesPerSecond

    /** Returns confirmed segments from [index], for incremental delivery. */
    fun segmentsFrom(index: Int) = accumulator.getSegmentsFrom(index)

    /** Returns the current live transcript state, for a listener that has just attached. */
    fun currentTranscriptUpdate() = TranscriptUpdate(
        partialText = accumulator.getCurrentPartial(),
        formattedPartial = accumulator.getFormattedPartial(vad.getCurrentSilenceDurationMs()),
        isFinal = lastSummary != null,
        isReset = true
    )

    private fun dispatchTranscription(isFinal: Boolean, isReset: Boolean = false) {
        onTranscriptionUpdate?.invoke(
            TranscriptUpdate(
                partialText = accumulator.getCurrentPartial(),
                formattedPartial = accumulator.getFormattedPartial(vad.getCurrentSilenceDurationMs()),
                isFinal = isFinal,
                isReset = isReset
            )
        )
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val DURATION_INTERVAL_MS = 250L
        const val RESTART_DELAY_MS = 150L
        const val MAX_RESTART_DELAY_MS = 10_000L
        const val MAX_BACKOFF_SHIFT = 6
        const val FINAL_RESULT_TIMEOUT_MS = 2_000L
    }
}
