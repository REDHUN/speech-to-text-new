package com.livespeechrecorder.live_speech_recorder

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.livespeechrecorder.live_speech_recorder.audio.AudioPlaybackManager
import com.livespeechrecorder.live_speech_recorder.audio.PlaybackState
import com.livespeechrecorder.live_speech_recorder.audio.RecordingStore
import com.livespeechrecorder.live_speech_recorder.formatting.TimedSegment
import com.livespeechrecorder.live_speech_recorder.recognition.AndroidSessionPlatform
import com.livespeechrecorder.live_speech_recorder.recognition.RecognitionCapabilityDetector
import com.livespeechrecorder.live_speech_recorder.recognition.RecognitionConfig
import com.livespeechrecorder.live_speech_recorder.recognition.RecognitionSessionManager
import com.livespeechrecorder.live_speech_recorder.recognition.RecordingSummary
import com.livespeechrecorder.live_speech_recorder.recognition.SessionPlatform
import com.livespeechrecorder.live_speech_recorder.recognition.TranscriptUpdate
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.util.UUID

/**
 * Flutter entry point. Commands arrive on the method channel and results are streamed through
 * event channels. All plugin state is confined to the main thread.
 */
class LiveSpeechRecorderPlugin : FlutterPlugin, MethodCallHandler {

    private class EventStream(
        messenger: BinaryMessenger,
        name: String,
        private val onAttached: (EventStream) -> Unit = {}
    ) : EventChannel.StreamHandler {
        private val channel = EventChannel(messenger, name)
        private var sink: EventChannel.EventSink? = null

        val hasListener get() = sink != null

        init {
            channel.setStreamHandler(this)
        }

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            sink = events
            onAttached(this)
        }

        override fun onCancel(arguments: Any?) {
            sink = null
        }

        fun send(value: Any?) {
            sink?.success(value)
        }

        fun close() {
            channel.setStreamHandler(null)
            sink = null
        }
    }

    private lateinit var methodChannel: MethodChannel
    private lateinit var transcriptionEvents: EventStream
    private lateinit var recordingStateEvents: EventStream
    private lateinit var playbackStateEvents: EventStream
    private lateinit var playbackPositionEvents: EventStream
    private lateinit var amplitudeEvents: EventStream
    private lateinit var durationEvents: EventStream
    private lateinit var errorEvents: EventStream

    private var context: Context? = null
    private var platform: SessionPlatform? = null
    private var playbackManager: AudioPlaybackManager? = null
    private var recordingStore: RecordingStore? = null
    private var sessionManager: RecognitionSessionManager? = null
    private var lastRecordedAudioPath: String? = null
    private var sentSegmentCount = 0

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val appContext = binding.applicationContext
        val messenger = binding.binaryMessenger
        context = appContext
        platform = AndroidSessionPlatform(appContext)
        recordingStore = RecordingStore(File(appContext.filesDir, "recordings"))

        methodChannel = MethodChannel(messenger, "$CHANNEL_PREFIX/methods")
        methodChannel.setMethodCallHandler(this)

        transcriptionEvents = EventStream(messenger, "$CHANNEL_PREFIX/transcription") {
            // A new listener receives the whole transcript, then only additions.
            sentSegmentCount = 0
            sessionManager?.let { manager -> sendTranscription(manager, manager.currentTranscriptUpdate()) }
        }
        recordingStateEvents = EventStream(messenger, "$CHANNEL_PREFIX/recording_state") {
            it.send(currentRecordingState())
        }
        playbackStateEvents = EventStream(messenger, "$CHANNEL_PREFIX/playback_state") {
            it.send(currentPlaybackState().name)
        }
        playbackPositionEvents = EventStream(messenger, "$CHANNEL_PREFIX/playback_position")
        amplitudeEvents = EventStream(messenger, "$CHANNEL_PREFIX/amplitude")
        durationEvents = EventStream(messenger, "$CHANNEL_PREFIX/duration")
        errorEvents = EventStream(messenger, "$CHANNEL_PREFIX/errors")

        playbackManager = AudioPlaybackManager(appContext).also { player ->
            player.onStateChanged = { playbackStateEvents.send(it.name) }
            player.onError = { code, message -> sendError(code, message, isFatal = false) }
            player.onPositionUpdated = { positionMs, durationMs ->
                playbackPositionEvents.send(mapOf("positionMs" to positionMs, "durationMs" to durationMs))
            }
        }
    }

    private fun currentRecordingState() = sessionManager?.wireState ?: "UNINITIALIZED"

    private fun currentPlaybackState() = playbackManager?.currentState ?: PlaybackState.IDLE

    private fun sendError(code: String, message: String, isFatal: Boolean) {
        errorEvents.send(mapOf("code" to code, "message" to message, "isFatal" to isFatal))
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            return
        }

        val ctx = context
        val sessionPlatform = platform
        val player = playbackManager
        val store = recordingStore
        if (ctx == null || sessionPlatform == null || player == null || store == null) {
            result.error("CONTEXT_UNAVAILABLE", "The plugin is not attached to a Flutter engine.", null)
            return
        }

        when (call.method) {
            "checkCapabilities" -> checkCapabilities(ctx, sessionPlatform, result)
            "initialize" -> initialize(call, sessionPlatform, result)
            "start" -> start(call, player, store, result)
            "pause" -> withSession(result) { it.pause(result::success) }
            "resume" -> withSession(result) { it.resume(result::success) }
            "stop" -> stop(result)
            "cancel" -> cancel(result)
            "getRecordingState" -> result.success(currentRecordingState())
            "getPlaybackState" -> result.success(currentPlaybackState().name)
            "listRecordings" -> listRecordings(sessionPlatform, store, result)
            "deleteRecording" -> deleteRecording(call, sessionPlatform, store, player, result)
            "play" -> play(call, player, result)
            "pausePlayback" -> result.success(player.pause())
            "resumePlayback" -> result.success(player.resume())
            "stopPlayback" -> {
                player.stop()
                result.success(true)
            }
            "seekTo" -> result.success(player.seekTo(call.argument<Number>("positionMs")?.toLong() ?: 0L))
            "setPlaybackSpeed" -> result.success(player.setPlaybackSpeed(call.argument<Number>("speed")?.toFloat() ?: 1f))
            "getPlaybackPosition" -> result.success(player.getCurrentPosition())
            "getPlaybackDuration" -> result.success(player.getDuration())
            "dispose" -> dispose(player, result)
            else -> result.notImplemented()
        }
    }

    private fun withSession(result: Result, action: (RecognitionSessionManager) -> Unit) {
        val manager = sessionManager
        if (manager == null) {
            result.error("NOT_INITIALIZED", "Call initialize() first.", null)
        } else {
            action(manager)
        }
    }

    private fun checkCapabilities(ctx: Context, sessionPlatform: SessionPlatform, result: Result) {
        sessionPlatform.runInBackground {
            val capability = RecognitionCapabilityDetector.detect(ctx)
            sessionPlatform.runOnMain {
                result.success(
                    mapOf(
                        "hasSpeechRecognizer" to capability.hasSpeechRecognizer,
                        "supportsAudioSourceInjection" to capability.supportsAudioSourceInjection,
                        "supportsOfflineRecognition" to capability.supportsOfflineRecognition,
                        "availableServices" to capability.availableServices,
                        "defaultServicePackage" to capability.defaultServicePackage,
                        "recommendedMode" to capability.recommendedMode
                    )
                )
            }
        }
    }

    private fun initialize(call: MethodCall, sessionPlatform: SessionPlatform, result: Result) {
        val existing = sessionManager
        if (existing?.isActive == true) {
            result.error("ALREADY_RECORDING", "Stop or cancel the recording before initializing again.", null)
            return
        }

        val defaults = RecognitionConfig()
        val language = call.argument<String>("language") ?: defaults.language
        val sampleRate = call.argument<Int>("sampleRate") ?: defaults.sampleRate
        val mode = call.argument<String>("recognitionMode") ?: defaults.recognitionMode
        if (language.isBlank()) {
            result.error("INVALID_ARGUMENT", "language must not be empty.", null)
            return
        }
        if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            result.error("INVALID_ARGUMENT", "sampleRate must be between $MIN_SAMPLE_RATE and $MAX_SAMPLE_RATE.", null)
            return
        }

        val config = RecognitionConfig(
            language = language,
            recognitionMode = mode,
            sampleRate = sampleRate,
            enablePunctuation = call.argument<Boolean>("enablePunctuation") ?: defaults.enablePunctuation,
            enableSegmented = call.argument<Boolean>("enableSegmented") ?: defaults.enableSegmented,
            enableBackgroundRecording = call.argument<Boolean>("enableBackgroundRecording") ?: defaults.enableBackgroundRecording,
            notificationTitle = call.argument<String>("notificationTitle") ?: defaults.notificationTitle,
            notificationText = call.argument<String>("notificationText") ?: defaults.notificationText
        )

        existing?.release {}
        sentSegmentCount = 0
        sessionManager = RecognitionSessionManager(sessionPlatform, config).also { manager ->
            manager.onTranscriptionUpdate = { update -> sendTranscription(manager, update) }
            manager.onAmplitudeUpdate = { amplitudeEvents.send(it) }
            manager.onDurationUpdate = { durationEvents.send(it) }
            manager.onStateChanged = { recordingStateEvents.send(it) }
            manager.onError = { code, message, isFatal -> sendError(code, message, isFatal) }
        }
        recordingStateEvents.send(currentRecordingState())
        result.success(true)
    }

    private fun sendTranscription(manager: RecognitionSessionManager, update: TranscriptUpdate) {
        if (manager !== sessionManager || !transcriptionEvents.hasListener) return

        // Without this the cost of each update would grow with the length of the transcript.
        val startIndex = if (update.isReset) 0 else sentSegmentCount
        val segments = manager.segmentsFrom(startIndex)
        sentSegmentCount = startIndex + segments.size
        transcriptionEvents.send(
            mapOf(
                "partialText" to update.partialText,
                "formattedPartial" to update.formattedPartial,
                "isFinal" to update.isFinal,
                "isReset" to update.isReset,
                "segmentStartIndex" to startIndex,
                "segments" to segments.map(::segmentToMap)
            )
        )
    }

    private fun segmentToMap(segment: TimedSegment) = mapOf(
        "text" to segment.text,
        "startTimeMs" to segment.startTimeMs,
        "endTimeMs" to segment.endTimeMs,
        "confidence" to segment.confidence,
        "isFinal" to segment.isFinal,
        "startsParagraph" to segment.startsParagraph
    )

    private fun start(call: MethodCall, player: AudioPlaybackManager, store: RecordingStore, result: Result) {
        withSession(result) { manager ->
            // Playing audio into the microphone would end up in the recording.
            if (!manager.isActive) player.stop()

            val customPath = call.argument<String>("outputPath")
            val outputFile = if (customPath.isNullOrBlank()) {
                store.newRecordingFile(UUID.randomUUID().toString().take(6))
            } else {
                File(customPath)
            }

            manager.start(outputFile) { outcome ->
                val path = outcome.audioPath
                if (path != null) {
                    lastRecordedAudioPath = path
                    result.success(path)
                } else {
                    result.error(outcome.errorCode ?: "START_FAILED", outcome.errorMessage ?: "Could not start recording.", null)
                }
            }
        }
    }

    private fun stop(result: Result) {
        withSession(result) { manager ->
            manager.stop { summary ->
                if (summary == null) {
                    result.error("NO_RECORDING", "There is no recording to stop.", null)
                } else {
                    result.success(summaryToMap(summary))
                }
            }
        }
    }

    private fun summaryToMap(summary: RecordingSummary) = mapOf(
        "audioPath" to summary.audioPath,
        "transcription" to summary.transcription,
        "durationMs" to summary.durationMs,
        "fileSizeBytes" to summary.fileSizeBytes,
        "sampleRate" to summary.sampleRate,
        "channels" to summary.channels,
        "failureCode" to summary.failureCode
    )

    private fun cancel(result: Result) {
        val manager = sessionManager
        if (manager == null) {
            result.success(false)
            return
        }
        manager.cancel { cancelled ->
            if (cancelled) lastRecordedAudioPath = null
            result.success(cancelled)
        }
    }

    private fun listRecordings(sessionPlatform: SessionPlatform, store: RecordingStore, result: Result) {
        val activePath = sessionManager?.activeAudioPath
        sessionPlatform.runInBackground {
            val recordings = store.list(activePath).map {
                mapOf(
                    "path" to it.path,
                    "sizeBytes" to it.sizeBytes,
                    "durationMs" to it.durationMs,
                    "modifiedAtMs" to it.modifiedAtMs
                )
            }
            sessionPlatform.runOnMain { result.success(recordings) }
        }
    }

    private fun deleteRecording(
        call: MethodCall,
        sessionPlatform: SessionPlatform,
        store: RecordingStore,
        player: AudioPlaybackManager,
        result: Result
    ) {
        val path = call.argument<String>("path")
        if (path.isNullOrBlank()) {
            result.error("INVALID_FILE_PATH", "No recording path was provided.", null)
            return
        }
        player.stop()
        val activePath = sessionManager?.activeAudioPath
        sessionPlatform.runInBackground {
            val deleted = store.delete(path, activePath)
            sessionPlatform.runOnMain {
                if (deleted && lastRecordedAudioPath == path) lastRecordedAudioPath = null
                result.success(deleted)
            }
        }
    }

    private fun play(call: MethodCall, player: AudioPlaybackManager, result: Result) {
        if (sessionManager?.isActive == true) {
            result.error("RECORDING_ACTIVE", "Stop the recording before playing audio.", null)
            return
        }
        val filePath = call.argument<String>("filePath") ?: lastRecordedAudioPath
        if (filePath.isNullOrBlank()) {
            result.error("INVALID_FILE_PATH", "No audio file path was provided.", null)
            return
        }
        result.success(player.play(filePath))
    }

    private fun dispose(player: AudioPlaybackManager, result: Result) {
        player.stop()
        val manager = sessionManager
        manager?.onStateChanged = null
        sessionManager = null
        lastRecordedAudioPath = null
        sentSegmentCount = 0
        recordingStateEvents.send(currentRecordingState())
        if (manager == null) {
            result.success(true)
        } else {
            manager.release { result.success(true) }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        listOf(
            transcriptionEvents, recordingStateEvents, playbackStateEvents,
            playbackPositionEvents, amplitudeEvents, durationEvents, errorEvents
        ).forEach { it.close() }

        // An active recording is finalized rather than deleted.
        val manager = sessionManager
        manager?.onTranscriptionUpdate = null
        manager?.onAmplitudeUpdate = null
        manager?.onDurationUpdate = null
        manager?.onStateChanged = null
        manager?.onError = null
        manager?.release {}
        sessionManager = null

        playbackManager?.release()
        playbackManager = null
        recordingStore = null
        platform = null
        context = null
    }

    private companion object {
        const val CHANNEL_PREFIX = "com.livespeechrecorder"
        const val MIN_SAMPLE_RATE = 8000
        const val MAX_SAMPLE_RATE = 48000
    }
}
