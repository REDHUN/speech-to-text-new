package com.livespeechrecorder.live_speech_recorder.recognition

data class RecognitionConfig(
    val language: String = "en-IN",
    val recognitionMode: String = MODE_AUTOMATIC,
    val sampleRate: Int = 16000,
    val enablePunctuation: Boolean = true,
    val enableSegmented: Boolean = true,
    val enableBackgroundRecording: Boolean = true,
    val notificationTitle: String = "Recording audio",
    val notificationText: String = "Microphone is in use"
) {
    companion object {
        const val MODE_OFFLINE_ONLY = "offlineOnly"
        const val MODE_ONLINE_ALLOWED = "onlineAllowed"
        const val MODE_AUTOMATIC = "automatic"
    }
}

data class BackendFailure(
    val code: String,
    val message: String,
    /** Whether a new session may succeed. */
    val isRecoverable: Boolean,
    /** Expected during normal use, such as silence. Not reported to the host. */
    val isRoutine: Boolean = false
)

/** Backend events. All callbacks are delivered on the main thread. */
interface BackendListener {
    fun onPartialResult(text: String)
    fun onSegmentResult(text: String, confidence: Double?)
    fun onSessionCompleted(finalText: String?, confidence: Double?)
    fun onError(failure: BackendFailure)
}

/**
 * Abstraction for speech recognition engines. Apart from [writePcm], which is called from a
 * background thread, every method must be called on the main thread.
 */
interface SpeechRecognitionBackend {
    fun initialize(listener: BackendListener)

    /**
     * Starts a session, replacing any current one.
     *
     * @param preRollProvider Supplies recent audio at the moment the session can accept it.
     */
    fun startSession(preRollProvider: () -> ByteArray?)

    fun writePcm(buffer: ByteArray, offset: Int, length: Int)

    /**
     * Signals the end of audio and asks for final results.
     *
     * @return true if [BackendListener.onSessionCompleted] or an error will follow.
     */
    fun finishSession(): Boolean

    fun release()
}
