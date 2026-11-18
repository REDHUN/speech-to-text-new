package com.livespeechrecorder.live_speech_recorder.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.File

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR
}

/**
 * Plays recorded WAV files. All methods must be called on the main thread.
 */
class AudioPlaybackManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var startWhenPrepared = true
    private var resumeOnFocusGain = false
    private var playbackSpeed = 1.0f

    // Held as Any because AudioFocusRequest does not exist before Android 8.
    private var focusRequest: Any? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener(::onAudioFocusChanged)
    private var noisyReceiverRegistered = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            emitPosition()
            if (currentState == PlaybackState.PLAYING) {
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    var currentState: PlaybackState = PlaybackState.IDLE
        private set

    var onStateChanged: ((PlaybackState) -> Unit)? = null
    var onPositionUpdated: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onError: ((code: String, message: String) -> Unit)? = null

    /**
     * Starts loading [filePath], replacing anything already playing.
     *
     * @return false if playback could not begin. Details are sent through [onError].
     */
    fun play(filePath: String): Boolean {
        stop()

        val file = File(filePath)
        if (!file.exists()) return failPlayback("FILE_NOT_FOUND", "Audio file does not exist: $filePath")
        if (file.length() <= WavFileWriter.HEADER_BYTES) {
            return failPlayback("AUDIO_EMPTY", "The recording contains no audio: $filePath")
        }
        if (!acquireAudioFocus()) {
            return failPlayback("AUDIO_FOCUS_DENIED", "Another app or a call is using audio output.")
        }

        val player = MediaPlayer()
        mediaPlayer = player
        isPrepared = false
        startWhenPrepared = true
        try {
            player.setAudioAttributes(audioAttributes)
            player.setDataSource(filePath)
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { onCompleted() }
            player.setOnErrorListener { _, what, extra ->
                failPlayback("PLAYBACK_ERROR", "MediaPlayer error: what=$what, extra=$extra")
                true
            }
            player.prepareAsync()
            return true
        } catch (e: Exception) {
            return failPlayback("PLAYBACK_INIT_FAILED", "Could not start playback: ${e.message}")
        }
    }

    private fun onPrepared(player: MediaPlayer) {
        isPrepared = true
        if (startWhenPrepared) {
            startPlayer(player)
        } else {
            updateState(PlaybackState.PAUSED)
            emitPosition()
        }
    }

    private fun onCompleted() {
        stopProgressTracker()
        updateState(PlaybackState.COMPLETED)
        emitPosition()
    }

    /** Applies the chosen speed and starts. Setting a non-zero speed itself starts the player. */
    private fun startPlayer(player: MediaPlayer) {
        try {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
            player.start()
            registerNoisyReceiver()
            updateState(PlaybackState.PLAYING)
            startProgressTracker()
        } catch (e: RuntimeException) {
            failPlayback("PLAYBACK_START_FAILED", "Could not start playback: ${e.message}")
        }
    }

    fun pause(): Boolean {
        resumeOnFocusGain = false
        val player = mediaPlayer ?: return false
        if (!isPrepared) {
            startWhenPrepared = false
            return true
        }
        if (currentState != PlaybackState.PLAYING) return false
        return pausePlayer(player)
    }

    private fun pausePlayer(player: MediaPlayer): Boolean {
        return try {
            player.pause()
            stopProgressTracker()
            unregisterNoisyReceiver()
            updateState(PlaybackState.PAUSED)
            emitPosition()
            true
        } catch (e: IllegalStateException) {
            onError?.invoke("PLAYBACK_PAUSE_FAILED", "Could not pause playback: ${e.message}")
            false
        }
    }

    fun resume(): Boolean {
        val player = mediaPlayer ?: return false
        if (!isPrepared) {
            startWhenPrepared = true
            return true
        }
        if (currentState != PlaybackState.PAUSED && currentState != PlaybackState.COMPLETED) return false
        if (!acquireAudioFocus()) {
            onError?.invoke("AUDIO_FOCUS_DENIED", "Another app or a call is using audio output.")
            return false
        }
        startPlayer(player)
        return currentState == PlaybackState.PLAYING
    }

    fun stop() {
        releasePlayer()
        if (currentState != PlaybackState.IDLE) updateState(PlaybackState.IDLE)
    }

    fun release() {
        releasePlayer()
        currentState = PlaybackState.IDLE
        onStateChanged = null
        onPositionUpdated = null
        onError = null
    }

    fun seekTo(positionMs: Long): Boolean {
        val player = mediaPlayer ?: return false
        if (!isPrepared) return false
        return try {
            val safePosition = positionMs.coerceIn(0L, player.duration.toLong())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                player.seekTo(safePosition, MediaPlayer.SEEK_CLOSEST)
            } else {
                player.seekTo(safePosition.toInt())
            }
            mainHandler.post { emitPosition() }
            true
        } catch (e: IllegalStateException) {
            onError?.invoke("SEEK_FAILED", "Seek failed: ${e.message}")
            false
        }
    }

    /**
     * Sets the speed for current and future playback. While paused the value is stored and
     * applied on resume, because applying it immediately would restart the audio.
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        playbackSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val player = mediaPlayer
        if (player == null || !isPrepared || currentState != PlaybackState.PLAYING) return true
        return try {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
            true
        } catch (e: RuntimeException) {
            onError?.invoke("SPEED_FAILED", "Could not set playback speed: ${e.message}")
            false
        }
    }

    fun getDuration(): Long {
        val player = mediaPlayer
        if (player == null || !isPrepared) return 0L
        return player.duration.toLong().coerceAtLeast(0L)
    }

    fun getCurrentPosition(): Long {
        val player = mediaPlayer
        if (player == null || !isPrepared) return 0L
        return player.currentPosition.toLong().coerceAtLeast(0L)
    }

    private fun failPlayback(code: String, message: String): Boolean {
        releasePlayer()
        updateState(PlaybackState.ERROR)
        onError?.invoke(code, message)
        return false
    }

    private fun releasePlayer() {
        stopProgressTracker()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        resumeOnFocusGain = false
        isPrepared = false
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun acquireAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestAudioFocusWithRequest()
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocusWithRequest(): Int {
        val request = focusRequest as AudioFocusRequest?
            ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
                .also { focusRequest = it }
        return audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (focusRequest as AudioFocusRequest?)?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun onAudioFocusChanged(change: Int) {
        val player = mediaPlayer ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (currentState == PlaybackState.PLAYING && pausePlayer(player)) {
                    resumeOnFocusGain = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    startPlayer(player)
                }
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(noisyReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        context.unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
    }

    private fun emitPosition() {
        if (mediaPlayer == null || !isPrepared) return
        onPositionUpdated?.invoke(getCurrentPosition(), getDuration())
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        mainHandler.post(progressRunnable)
    }

    private fun stopProgressTracker() {
        mainHandler.removeCallbacks(progressRunnable)
    }

    private fun updateState(newState: PlaybackState) {
        currentState = newState
        onStateChanged?.invoke(newState)
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}
