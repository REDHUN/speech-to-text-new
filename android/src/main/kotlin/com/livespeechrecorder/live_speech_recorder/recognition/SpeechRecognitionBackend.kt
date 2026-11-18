package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context

data class RecognitionConfig(
    val language: String = "en-IN",
    val recognitionMode: String = "automatic", // "offlineOnly", "onlineAllowed", "automatic"
    val sampleRate: Int = 16000,
    val enablePunctuation: Boolean = true,
    val enableSegmented: Boolean = true
)

interface BackendListener {
    fun onPartialResult(text: String)
    fun onSegmentResult(text: String)
    fun onSessionCompleted(finalText: String?)
    fun onError(code: String, message: String, isRecoverable: Boolean)
}

/**
 * Abstraction for speech recognition engines.
 *
 * Allows interchangeable recognition engines (native Android SpeechRecognizer via ParcelFileDescriptor,
 * on-device streaming engine, Vosk/Sherpa adapter, etc.) without altering the public API.
 */
interface SpeechRecognitionBackend {
    fun initialize(context: Context, config: RecognitionConfig, listener: BackendListener)
    fun startSession(sessionId: String): Boolean
    fun writePcm(buffer: ByteArray, offset: Int, length: Int)
    fun stopSession()
    fun release()
    val isSessionActive: Boolean
}
