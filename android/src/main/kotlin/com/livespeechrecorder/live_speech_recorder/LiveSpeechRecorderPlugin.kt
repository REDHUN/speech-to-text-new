package com.livespeechrecorder.live_speech_recorder

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.livespeechrecorder.live_speech_recorder.audio.AudioPlaybackManager
import com.livespeechrecorder.live_speech_recorder.audio.PlaybackState
import com.livespeechrecorder.live_speech_recorder.recognition.RecognitionCapabilityDetector
import com.livespeechrecorder.live_speech_recorder.recognition.RecognitionConfig
import com.livespeechrecorder.live_speech_recorder.recognition.RecognitionSessionManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.util.UUID

/**
 * Main Flutter plugin implementation for LiveSpeechRecorder.
 *
 * Implements MethodChannel for commands and 6 EventChannels for real-time streaming:
 * - transcription
 * - recording_state
 * - playback_state
 * - amplitude
 * - duration
 * - errors
 */
class LiveSpeechRecorderPlugin : FlutterPlugin, MethodCallHandler {

    private lateinit var methodChannel: MethodChannel
    private lateinit var transcriptionChannel: EventChannel
    private lateinit var recordingStateChannel: EventChannel
    private lateinit var playbackStateChannel: EventChannel
    private lateinit var amplitudeChannel: EventChannel
    private lateinit var durationChannel: EventChannel
    private lateinit var errorChannel: EventChannel

    private var transcriptionSink: EventChannel.EventSink? = null
    private var recordingStateSink: EventChannel.EventSink? = null
    private var playbackStateSink: EventChannel.EventSink? = null
    private var amplitudeSink: EventChannel.EventSink? = null
    private var durationSink: EventChannel.EventSink? = null
    private var errorSink: EventChannel.EventSink? = null

    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sessionManager: RecognitionSessionManager? = null
    private val playbackManager = AudioPlaybackManager()

    private var currentConfig = RecognitionConfig()
    private var lastRecordedAudioPath: String? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        val messenger = binding.binaryMessenger

        methodChannel = MethodChannel(messenger, "com.livespeechrecorder/methods")
        methodChannel.setMethodCallHandler(this)

        transcriptionChannel = EventChannel(messenger, "com.livespeechrecorder/transcription")
        transcriptionChannel.setStreamHandler(object : SimpleStreamHandler() {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                transcriptionSink = events
            }
            override fun onCancel(arguments: Any?) {
                transcriptionSink = null
            }
        })

        recordingStateChannel = EventChannel(messenger, "com.livespeechrecorder/recording_state")
        recordingStateChannel.setStreamHandler(object : SimpleStreamHandler() {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                recordingStateSink = events
            }
            override fun onCancel(arguments: Any?) {
                recordingStateSink = null
            }
        })

        playbackStateChannel = EventChannel(messenger, "com.livespeechrecorder/playback_state")
        playbackStateChannel.setStreamHandler(object : SimpleStreamHandler() {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                playbackStateSink = events
            }
            override fun onCancel(arguments: Any?) {
                playbackStateSink = null
            }
        })

        amplitudeChannel = EventChannel(messenger, "com.livespeechrecorder/amplitude")
        amplitudeChannel.setStreamHandler(object : SimpleStreamHandler() {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                amplitudeSink = events
            }
            override fun onCancel(arguments: Any?) {
                amplitudeSink = null
            }
        })

        durationChannel = EventChannel(messenger, "com.livespeechrecorder/duration")
        durationChannel.setStreamHandler(object : SimpleStreamHandler() {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                durationSink = events
            }
            override fun onCancel(arguments: Any?) {
                durationSink = null
            }
        })

        errorChannel = EventChannel(messenger, "com.livespeechrecorder/errors")
        errorChannel.setStreamHandler(object : SimpleStreamHandler() {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                errorSink = events
            }
            override fun onCancel(arguments: Any?) {
                errorSink = null
            }
        })

        setupPlaybackListeners()
    }

    private fun setupPlaybackListeners() {
        playbackManager.onStateChanged = { state ->
            mainHandler.post {
                playbackStateSink?.success(state.name)
            }
        }
        playbackManager.onError = { code, msg ->
            mainHandler.post {
                errorSink?.success(mapOf("code" to code, "message" to msg))
            }
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val ctx = context ?: run {
            result.error("CONTEXT_UNAVAILABLE", "Android Context is null", null)
            return
        }

        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }

            "checkCapabilities" -> {
                val cap = RecognitionCapabilityDetector.detect(ctx)
                result.success(
                    mapOf(
                        "hasSpeechRecognizer" to cap.hasSpeechRecognizer,
                        "supportsAudioSourceInjection" to cap.supportsAudioSourceInjection,
                        "supportsOfflineRecognition" to cap.supportsOfflineRecognition,
                        "availableServices" to cap.availableServices,
                        "defaultServicePackage" to cap.defaultServicePackage,
                        "recommendedMode" to cap.recommendedMode
                    )
                )
            }

            "initialize" -> {
                val language = call.argument<String>("language") ?: "en-IN"
                val sampleRate = call.argument<Int>("sampleRate") ?: 16000
                val recognitionMode = call.argument<String>("recognitionMode") ?: "automatic"
                val enablePunctuation = call.argument<Boolean>("enablePunctuation") ?: true
                val enableSegmented = call.argument<Boolean>("enableSegmented") ?: true

                currentConfig = RecognitionConfig(
                    language = language,
                    recognitionMode = recognitionMode,
                    sampleRate = sampleRate,
                    enablePunctuation = enablePunctuation,
                    enableSegmented = enableSegmented
                )

                // Clean existing session if any
                sessionManager?.cancel()

                val manager = RecognitionSessionManager(ctx, currentConfig)
                manager.onTranscriptionUpdate = { full, partial, confirmed, isFinal, segments ->
                    mainHandler.post {
                        transcriptionSink?.success(
                            mapOf(
                                "fullText" to full,
                                "partialText" to partial,
                                "confirmedText" to confirmed,
                                "isFinal" to isFinal,
                                "segments" to segments.map { seg ->
                                    mapOf(
                                        "text" to seg.text,
                                        "startTimeMs" to seg.startTimeMs,
                                        "endTimeMs" to seg.endTimeMs,
                                        "confidence" to seg.confidence,
                                        "isFinal" to seg.isFinal
                                    )
                                }
                            )
                        )
                    }
                }

                manager.onAmplitudeUpdate = { norm, db ->
                    mainHandler.post {
                        amplitudeSink?.success(norm)
                    }
                }

                manager.onDurationUpdate = { dur ->
                    mainHandler.post {
                        durationSink?.success(dur)
                    }
                }

                manager.onStateChanged = { state ->
                    mainHandler.post {
                        recordingStateSink?.success(state)
                    }
                }

                manager.onError = { code, msg ->
                    mainHandler.post {
                        errorSink?.success(mapOf("code" to code, "message" to msg))
                    }
                }

                sessionManager = manager
                result.success(true)
            }

            "start" -> {
                val manager = sessionManager ?: run {
                    result.error("NOT_INITIALIZED", "Call initialize() before start()", null)
                    return
                }

                // Stop playback if playing to prevent acoustic feedback loop
                if (playbackManager.currentState == PlaybackState.PLAYING) {
                    playbackManager.stop()
                }

                val customPath = call.argument<String>("outputPath")
                val outputFile = if (!customPath.isNullOrBlank()) {
                    File(customPath)
                } else {
                    val recordingsDir = File(ctx.cacheDir, "recordings").apply { mkdirs() }
                    File(recordingsDir, "recording_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.wav")
                }

                lastRecordedAudioPath = outputFile.absolutePath
                val started = manager.start(outputFile)
                if (started) {
                    result.success(outputFile.absolutePath)
                } else {
                    result.error("START_FAILED", "Failed to start audio recording and transcription session", null)
                }
            }

            "pause" -> {
                sessionManager?.pause()
                result.success(true)
            }

            "resume" -> {
                sessionManager?.resume()
                result.success(true)
            }

            "stop" -> {
                val manager = sessionManager
                if (manager == null) {
                    result.error("NOT_INITIALIZED", "Recorder session manager is null", null)
                    return
                }

                val (durationMs, finalTranscript) = manager.stop()
                val audioPath = lastRecordedAudioPath ?: ""
                val file = File(audioPath)
                val fileSize = if (file.exists()) file.length() else 0L

                result.success(
                    mapOf(
                        "audioPath" to audioPath,
                        "transcription" to finalTranscript,
                        "durationMs" to durationMs,
                        "fileSizeBytes" to fileSize,
                        "sampleRate" to currentConfig.sampleRate,
                        "channels" to 1
                    )
                )
            }

            "cancel" -> {
                sessionManager?.cancel()
                lastRecordedAudioPath = null
                result.success(true)
            }

            // Playback controls
            "play" -> {
                val filePath = call.argument<String>("filePath") ?: lastRecordedAudioPath
                if (filePath.isNullOrBlank()) {
                    result.error("INVALID_FILE_PATH", "No audio file path provided for playback", null)
                    return
                }
                val success = playbackManager.play(filePath)
                result.success(success)
            }

            "pausePlayback" -> {
                playbackManager.pause()
                result.success(true)
            }

            "resumePlayback" -> {
                playbackManager.resume()
                result.success(true)
            }

            "stopPlayback" -> {
                playbackManager.stop()
                result.success(true)
            }

            "seekTo" -> {
                val positionMs = call.argument<Number>("positionMs")?.toLong() ?: 0L
                playbackManager.seekTo(positionMs)
                result.success(true)
            }

            "setPlaybackSpeed" -> {
                val speed = call.argument<Number>("speed")?.toFloat() ?: 1.0f
                playbackManager.setPlaybackSpeed(speed)
                result.success(true)
            }

            "getPlaybackPosition" -> {
                result.success(playbackManager.getCurrentPosition())
            }

            "getPlaybackDuration" -> {
                result.success(playbackManager.getDuration())
            }

            "dispose" -> {
                sessionManager?.cancel()
                sessionManager = null
                playbackManager.stop()
                result.success(true)
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        transcriptionChannel.setStreamHandler(null)
        recordingStateChannel.setStreamHandler(null)
        playbackStateChannel.setStreamHandler(null)
        amplitudeChannel.setStreamHandler(null)
        durationChannel.setStreamHandler(null)
        errorChannel.setStreamHandler(null)

        sessionManager?.cancel()
        sessionManager = null
        playbackManager.stop()
        context = null
    }

    private abstract class SimpleStreamHandler : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {}
        override fun onCancel(arguments: Any?) {}
    }
}
