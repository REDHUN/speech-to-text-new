package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Speech recognition through Android's SpeechRecognizer, fed by the shared capture session
 * through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE).
 */
class AndroidSpeechRecognizerBackend(
    private val context: Context,
    private val config: RecognitionConfig
) : SpeechRecognitionBackend {

    private enum class Engine { ON_DEVICE, ONLINE }

    private class AudioPipe(val readEnd: ParcelFileDescriptor, val writeEnd: ParcelFileDescriptor) {
        val lock = Any()

        /** Audio to send ahead of live audio. Written by the audio thread, never the main thread. */
        val pendingReplay = AtomicReference<ByteArray?>(null)
        var readEndClosed = false
        var writeEndClosed = false

        fun closeReadEnd() = synchronized(lock) {
            if (!readEndClosed) {
                readEndClosed = true
                closeQuietly(readEnd)
            }
        }

        fun closeWriteEnd() = synchronized(lock) {
            if (!writeEndClosed) {
                writeEndClosed = true
                closeQuietly(writeEnd)
            }
        }

        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            try {
                descriptor.close()
            } catch (ignored: IOException) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }

    private var listener: BackendListener? = null
    private var recognizer: SpeechRecognizer? = null
    private var supportProbe: SpeechRecognizer? = null
    private var engine: Engine? = null
    private var generation = 0
    private var sessionLive = false
    private var finishing = false

    @Volatile private var pipe: AudioPipe? = null
    @Volatile private var isAcceptingAudio = false

    override fun initialize(listener: BackendListener) {
        this.listener = listener
    }

    override fun startSession(preRollProvider: () -> ByteArray?) {
        val sessionGeneration = ++generation
        teardownSession()
        finishing = false

        resolveEngine(sessionGeneration) { resolved ->
            if (sessionGeneration == generation) {
                openSession(resolved, sessionGeneration, preRollProvider)
            }
        }
    }

    /** Picks the engine once per recording, honoring the requested recognition mode. */
    private fun resolveEngine(sessionGeneration: Int, onResolved: (Engine) -> Unit) {
        engine?.let {
            onResolved(it)
            return
        }

        val mode = config.recognitionMode
        if (mode == RecognitionConfig.MODE_ONLINE_ALLOWED) {
            engine = Engine.ONLINE
            onResolved(Engine.ONLINE)
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            useFallbackOrFail(onResolved, "On-device speech recognition is not available on this device.")
            return
        }

        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        supportProbe = probe
        probe.checkRecognitionSupport(createBaseIntent(), mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                finishProbe(probe)
                if (sessionGeneration != generation) return
                if (containsLanguage(support.installedOnDeviceLanguages)) {
                    engine = Engine.ON_DEVICE
                    onResolved(Engine.ON_DEVICE)
                } else {
                    useFallbackOrFail(onResolved, "The on-device language pack for ${config.language} is not installed.")
                }
            }

            override fun onError(error: Int) {
                finishProbe(probe)
                if (sessionGeneration != generation) return
                // Support could not be checked. Offline mode still tries on-device, which
                // reports a precise language error if the pack is missing.
                if (config.recognitionMode == RecognitionConfig.MODE_OFFLINE_ONLY) {
                    engine = Engine.ON_DEVICE
                    onResolved(Engine.ON_DEVICE)
                } else {
                    engine = Engine.ONLINE
                    onResolved(Engine.ONLINE)
                }
            }
        })
    }

    private fun finishProbe(probe: SpeechRecognizer) {
        probe.destroy()
        if (supportProbe === probe) supportProbe = null
    }

    private fun useFallbackOrFail(onResolved: (Engine) -> Unit, offlineFailure: String) {
        if (config.recognitionMode == RecognitionConfig.MODE_OFFLINE_ONLY) {
            listener?.onError(BackendFailure("OFFLINE_UNAVAILABLE", offlineFailure, isRecoverable = false))
        } else {
            engine = Engine.ONLINE
            onResolved(Engine.ONLINE)
        }
    }

    private fun containsLanguage(languages: List<String>): Boolean {
        val wanted = normalizeLanguage(config.language)
        return languages.any { normalizeLanguage(it) == wanted }
    }

    private fun normalizeLanguage(tag: String) = tag.replace('_', '-').lowercase(Locale.ROOT)

    private fun openSession(
        selectedEngine: Engine,
        sessionGeneration: Int,
        preRollProvider: () -> ByteArray?
    ) {
        try {
            val speechRecognizer = when (selectedEngine) {
                Engine.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                Engine.ONLINE -> SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = speechRecognizer

            val descriptors = ParcelFileDescriptor.createPipe()
            val openedPipe = AudioPipe(descriptors[0], descriptors[1])
            pipe = openedPipe

            speechRecognizer.setRecognitionListener(createRecognitionListener(sessionGeneration))
            speechRecognizer.startListening(createSessionIntent(descriptors[0]))
            sessionLive = true

            // Android forbids waiting on a pipe from the main thread, so the audio thread sends this.
            openedPipe.pendingReplay.set(preRollProvider())
            isAcceptingAudio = true
        } catch (e: Exception) {
            teardownSession()
            val reason = e.message ?: e.javaClass.simpleName
            listener?.onError(
                BackendFailure("START_SESSION_FAILED", "Could not start recognition: $reason", isRecoverable = true)
            )
        }
    }

    private fun createBaseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
    }

    private fun createSessionIntent(audioSource: ParcelFileDescriptor) = createBaseIntent().apply {
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, engine == Engine.ON_DEVICE)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, config.sampleRate)
        if (config.enableSegmented) {
            // The session then lasts until the audio source is closed.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
    }

    override fun writePcm(buffer: ByteArray, offset: Int, length: Int) {
        val activePipe = pipe ?: return
        if (!isAcceptingAudio || length <= 0) return

        activePipe.pendingReplay.getAndSet(null)?.let { writeToPipe(activePipe, it, 0, it.size) }
        writeToPipe(activePipe, buffer, offset, length)
    }

    /**
     * Writes without ever blocking on a stuck reader: each write waits for POLLOUT and stays
     * within PIPE_BUF. A reader that stops draining is reported instead of hanging this thread.
     * Must not be called on the main thread.
     */
    private fun writeToPipe(target: AudioPipe, buffer: ByteArray, offset: Int, length: Int) {
        val pollFd = StructPollfd().apply {
            fd = target.writeEnd.fileDescriptor
            events = OsConstants.POLLOUT.toShort()
        }
        val errorEvents = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
        var position = offset
        var remaining = length
        var lastProgressAt = SystemClock.elapsedRealtime()

        while (remaining > 0) {
            if (pipe !== target || !isAcceptingAudio) return
            try {
                if (Os.poll(arrayOf(pollFd), POLL_TIMEOUT_MS) == 0) {
                    if (SystemClock.elapsedRealtime() - lastProgressAt >= STALL_TIMEOUT_MS) {
                        reportPipeProblem(target, stalled = true)
                        return
                    }
                    continue
                }
                if (pollFd.revents.toInt() and errorEvents != 0) {
                    reportPipeProblem(target, stalled = false)
                    return
                }
                val written = synchronized(target.lock) {
                    if (target.writeEndClosed) return
                    Os.write(target.writeEnd.fileDescriptor, buffer, position, minOf(remaining, PIPE_ATOMIC_BYTES))
                }
                position += written
                remaining -= written
                lastProgressAt = SystemClock.elapsedRealtime()
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                reportPipeProblem(target, stalled = false)
                return
            } catch (e: IOException) {
                return
            }
        }
    }

    private fun reportPipeProblem(target: AudioPipe, stalled: Boolean) {
        mainHandler.post {
            if (pipe !== target || !isAcceptingAudio) return@post
            val failure = if (stalled) {
                BackendFailure(
                    "AUDIO_INJECTION_UNSUPPORTED",
                    "The speech recognition service is not reading the shared audio stream.",
                    isRecoverable = false
                )
            } else {
                BackendFailure("RECOGNIZER_DISCONNECTED", "The speech recognition service stopped reading audio.", isRecoverable = true)
            }
            generation++
            teardownSession()
            listener?.onError(failure)
        }
    }

    override fun finishSession(): Boolean {
        val activeRecognizer = recognizer
        if (!sessionLive || activeRecognizer == null) {
            // A session still being set up has nothing to finish.
            generation++
            teardownSession()
            return false
        }
        isAcceptingAudio = false
        finishing = true
        pipe?.closeWriteEnd()
        activeRecognizer.stopListening()
        return true
    }

    override fun release() {
        generation++
        teardownSession()
        supportProbe?.destroy()
        supportProbe = null
        listener = null
    }

    private fun teardownSession() {
        isAcceptingAudio = false
        sessionLive = false
        closePipe()
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    private fun closePipe() {
        pipe?.let {
            it.closeWriteEnd()
            it.closeReadEnd()
        }
        pipe = null
    }

    private fun createRecognitionListener(sessionGeneration: Int) = object : RecognitionListener {
        private val isCurrent get() = sessionGeneration == generation

        override fun onReadyForSpeech(params: Bundle?) {
            // The service holds its own copy now. Closing ours lets a dead reader be detected.
            if (isCurrent) pipe?.closeReadEnd()
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!isCurrent) return
            val wasFinishing = finishing
            endSession()
            if (wasFinishing) {
                listener?.onSessionCompleted(null, null)
            } else {
                listener?.onError(mapError(error))
            }
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent) return
            endSession()
            listener?.onSessionCompleted(firstResult(results), firstConfidence(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent) return
            firstResult(partialResults)?.takeIf { it.isNotBlank() }?.let { listener?.onPartialResult(it) }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!isCurrent) return
            firstResult(segmentResults)?.takeIf { it.isNotBlank() }?.let {
                listener?.onSegmentResult(it, firstConfidence(segmentResults))
            }
        }

        override fun onEndOfSegmentedSession() {
            if (!isCurrent) return
            endSession()
            listener?.onSessionCompleted(null, null)
        }
    }

    private fun endSession() {
        isAcceptingAudio = false
        sessionLive = false
        finishing = false
        closePipe()
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun firstConfidence(results: Bundle?): Double? =
        results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.firstOrNull()
            ?.takeIf { it in 0f..1f }
            ?.toDouble()

    private fun mapError(error: Int): BackendFailure {
        val language = config.language
        return when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                BackendFailure("SPEECH_TIMEOUT", "No speech detected.", isRecoverable = true, isRoutine = true)
            SpeechRecognizer.ERROR_NO_MATCH ->
                BackendFailure("NO_MATCH", "No speech was recognized.", isRecoverable = true, isRoutine = true)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                BackendFailure("RECOGNIZER_BUSY", "The recognition service is busy.", isRecoverable = true)
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                BackendFailure("TOO_MANY_REQUESTS", "The recognition service is rate limiting requests.", isRecoverable = true)
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK ->
                BackendFailure("NETWORK_ERROR", "Speech recognition network error.", isRecoverable = engine != Engine.ON_DEVICE)
            SpeechRecognizer.ERROR_AUDIO ->
                BackendFailure("AUDIO_ERROR", "The recognizer could not read audio.", isRecoverable = true)
            SpeechRecognizer.ERROR_SERVER ->
                BackendFailure("SERVER_ERROR", "Recognition server error.", isRecoverable = true)
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                BackendFailure("SERVER_DISCONNECTED", "The recognition service disconnected.", isRecoverable = true)
            SpeechRecognizer.ERROR_CLIENT ->
                BackendFailure("CLIENT_ERROR", "Recognition client error.", isRecoverable = true)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                BackendFailure("PERMISSION_DENIED", "Speech recognition lacks permission.", isRecoverable = false)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                mapLanguageError(error, language)
            else ->
                BackendFailure("UNKNOWN_ERROR", "Speech recognition error $error.", isRecoverable = true)
        }
    }

    private fun mapLanguageError(error: Int, language: String): BackendFailure {
        if (engine == Engine.ON_DEVICE && config.recognitionMode == RecognitionConfig.MODE_AUTOMATIC) {
            engine = Engine.ONLINE
            return BackendFailure("LANGUAGE_UNAVAILABLE", "Switching to online recognition.", isRecoverable = true, isRoutine = true)
        }
        val code = if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) "LANGUAGE_NOT_SUPPORTED" else "LANGUAGE_UNAVAILABLE"
        return BackendFailure(code, "Language is not available: $language", isRecoverable = false)
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 100
        const val PIPE_ATOMIC_BYTES = 4096
        const val STALL_TIMEOUT_MS = 8_000L
    }
}
