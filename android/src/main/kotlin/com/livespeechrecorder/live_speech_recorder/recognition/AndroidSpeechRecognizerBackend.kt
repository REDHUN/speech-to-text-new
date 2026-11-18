package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream

/**
 * Speech recognition backend leveraging Android's native SpeechRecognizer.
 *
 * In Android 13+ (API 33+), audio is piped directly from the shared AudioRecord session
 * into SpeechRecognizer using RecognizerIntent.EXTRA_AUDIO_SOURCE, guaranteeing a single
 * microphone capture session.
 */
class AndroidSpeechRecognizerBackend : SpeechRecognitionBackend {

    private var context: Context? = null
    private var config: RecognitionConfig = RecognitionConfig()
    private var listener: BackendListener? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pipe: Array<ParcelFileDescriptor>? = null
    private var pipeOutputStream: OutputStream? = null

    @Volatile
    override var isSessionActive: Boolean = false
        private set

    override fun initialize(context: Context, config: RecognitionConfig, listener: BackendListener) {
        this.context = context
        this.config = config
        this.listener = listener
    }

    override fun startSession(sessionId: String): Boolean {
        val ctx = context ?: return false

        // Android SpeechRecognizer must be created and invoked on the main thread
        mainHandler.post {
            try {
                cleanupCurrentSession()

                // Check offline capability if requested
                if (config.recognitionMode == "offlineOnly") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                            listener?.onError(
                                "OFFLINE_UNAVAILABLE",
                                "On-device speech recognition is not supported or offline language pack not installed for ${config.language}.",
                                false
                            )
                            return@post
                        }
                    } else {
                        listener?.onError(
                            "OFFLINE_UNSUPPORTED_API",
                            "On-device offline recognition requires Android 12 (API 31) or newer.",
                            false
                        )
                        return@post
                    }
                }

                // Create recognizer
                val recognizer = if (config.recognitionMode == "offlineOnly" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(ctx)
                }

                if (recognizer == null) {
                    listener?.onError("RECOGNIZER_CREATION_FAILED", "Failed to create SpeechRecognizer instance.", true)
                    return@post
                }

                speechRecognizer = recognizer

                // Prepare audio injection pipe if supported (Android 13+)
                val p = ParcelFileDescriptor.createPipe()
                pipe = p
                val readPipe = p[0]
                val writePipe = p[1]
                pipeOutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writePipe)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                    if (config.recognitionMode == "offlineOnly") {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }

                    // Modern Android 13+ audio source injection
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, config.sampleRate)

                        if (config.enableSegmented) {
                            putExtra(
                                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
                            )
                        }
                    }
                }

                recognizer.setRecognitionListener(createRecognitionListener())
                recognizer.startListening(intent)
                isSessionActive = true
            } catch (e: Exception) {
                isSessionActive = false
                listener?.onError("START_SESSION_FAILED", "Failed to start recognition session: ${e.message}", true)
            }
        }
        return true
    }

    override fun writePcm(buffer: ByteArray, offset: Int, length: Int) {
        if (!isSessionActive || length <= 0) return
        try {
            pipeOutputStream?.write(buffer, offset, length)
            pipeOutputStream?.flush()
        } catch (e: Exception) {
            // Pipe write error (e.g. Broken pipe if recognizer closed its read end)
        }
    }

    override fun stopSession() {
        isSessionActive = false
        mainHandler.post {
            try {
                pipeOutputStream?.flush()
                pipeOutputStream?.close()
            } catch (ignored: Exception) {
            } finally {
                pipeOutputStream = null
            }
            try {
                speechRecognizer?.stopListening()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun release() {
        isSessionActive = false
        mainHandler.post {
            cleanupCurrentSession()
            speechRecognizer = null
            context = null
        }
    }

    private fun cleanupCurrentSession() {
        try {
            pipeOutputStream?.close()
        } catch (ignored: Exception) {}
        pipeOutputStream = null

        pipe?.let {
            try { it[0].close() } catch (ignored: Exception) {}
            try { it[1].close() } catch (ignored: Exception) {}
        }
        pipe = null

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (ignored: Exception) {}
        speechRecognizer = null
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val (code, message, isRecoverable) = mapErrorCode(error)
                isSessionActive = false
                listener?.onError(code, message, isRecoverable)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                isSessionActive = false
                listener?.onSessionCompleted(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    listener?.onPartialResult(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onSegmentResults(segmentResults: Bundle) {
                val matches = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    listener?.onSegmentResult(text)
                }
            }

            override fun onEndOfSegmentedSession() {
                isSessionActive = false
                listener?.onSessionCompleted(null)
            }
        }
    }

    private fun mapErrorCode(error: Int): Triple<String, String, Boolean> {
        return when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                Triple("SPEECH_TIMEOUT", "No speech detected within expected timeout.", true)
            SpeechRecognizer.ERROR_NO_MATCH ->
                Triple("NO_MATCH", "No speech recognition match found.", true)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                Triple("RECOGNIZER_BUSY", "Recognition service busy. Will retry.", true)
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK ->
                Triple("NETWORK_ERROR", "Speech recognition network error.", config.recognitionMode != "offlineOnly")
            SpeechRecognizer.ERROR_AUDIO ->
                Triple("AUDIO_ERROR", "Audio recording/pipe error.", true)
            SpeechRecognizer.ERROR_SERVER ->
                Triple("SERVER_ERROR", "Recognition server error.", true)
            SpeechRecognizer.ERROR_CLIENT ->
                Triple("CLIENT_ERROR", "Client side recognition error.", true)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                Triple("PERMISSION_DENIED", "Insufficient permissions for speech recognition.", false)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                Triple("LANGUAGE_NOT_SUPPORTED", "Requested language is not supported: ${config.language}", false)
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                Triple("LANGUAGE_UNAVAILABLE", "Language pack is currently unavailable: ${config.language}", false)
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                Triple("SERVER_DISCONNECTED", "Speech service disconnected. Will reconnect.", true)
            else ->
                Triple("UNKNOWN_ERROR", "Unknown speech recognition error code $error.", true)
        }
    }
}
