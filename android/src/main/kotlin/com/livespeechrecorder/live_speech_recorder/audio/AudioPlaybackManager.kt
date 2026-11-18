package com.livespeechrecorder.live_speech_recorder.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR
}

/**
 * Handles audio playback for the recorded WAV files using native Android MediaPlayer.
 */
class AudioPlaybackManager {

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    var currentState: PlaybackState = PlaybackState.IDLE
        private set

    var onStateChanged: ((PlaybackState) -> Unit)? = null
    var onPositionUpdated: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onError: ((code: String, message: String) -> Unit)? = null

    /**
     * Starts playback of the given audio file.
     */
    fun play(filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) {
            updateState(PlaybackState.ERROR)
            onError?.invoke("FILE_NOT_FOUND", "Playback audio file does not exist: $filePath")
            return false
        }
        if (file.length() <= 44L) {
            updateState(PlaybackState.ERROR)
            onError?.invoke("AUDIO_EMPTY", "Recorded audio is empty (no sound captured): $filePath")
            return false
        }

        stop()

        try {
            val player = MediaPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    mp.start()
                    updateState(PlaybackState.PLAYING)
                    startProgressTracker()
                }
                setOnCompletionListener {
                    stopProgressTracker()
                    updateState(PlaybackState.COMPLETED)
                }
                setOnErrorListener { _, what, extra ->
                    stopProgressTracker()
                    updateState(PlaybackState.ERROR)
                    onError?.invoke("PLAYBACK_ERROR", "MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
            return true
        } catch (e: Exception) {
            updateState(PlaybackState.ERROR)
            onError?.invoke("PLAYBACK_INIT_FAILED", "Failed to start playback: ${e.message}")
            return false
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    updateState(PlaybackState.PAUSED)
                    stopProgressTracker()
                }
            }
        } catch (e: Exception) {
            onError?.invoke("PLAYBACK_PAUSE_FAILED", "Failed to pause playback: ${e.message}")
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    updateState(PlaybackState.PLAYING)
                    startProgressTracker()
                }
            }
        } catch (e: Exception) {
            onError?.invoke("PLAYBACK_RESUME_FAILED", "Failed to resume playback: ${e.message}")
        }
    }

    fun stop() {
        stopProgressTracker()
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                reset()
                release()
            }
        } catch (e: Exception) {
            // Ignore release errors
        } finally {
            mediaPlayer = null
            updateState(PlaybackState.IDLE)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.let {
                val safePos = positionMs.coerceIn(0L, it.duration.toLong())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.seekTo(safePos, MediaPlayer.SEEK_CLOSEST)
                } else {
                    it.seekTo(safePos.toInt())
                }
            }
        } catch (e: Exception) {
            onError?.invoke("SEEK_FAILED", "Seek failed: ${e.message}")
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mediaPlayer?.let {
                    val params = it.playbackParams
                    params.speed = speed.coerceIn(0.5f, 2.0f)
                    it.playbackParams = params
                }
            }
        } catch (e: Exception) {
            onError?.invoke("SPEED_FAILED", "Setting playback speed failed: ${e.message}")
        }
    }

    fun getDuration(): Long {
        return try {
            mediaPlayer?.duration?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getCurrentPosition(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && currentState == PlaybackState.PLAYING) {
                    try {
                        val current = mp.currentPosition.toLong()
                        val duration = mp.duration.toLong()
                        onPositionUpdated?.invoke(current, duration)
                        mainHandler.postDelayed(this, 100)
                    } catch (e: Exception) {
                        // Player might have finished or released
                    }
                }
            }
        }
        mainHandler.post(progressRunnable!!)
    }

    private fun stopProgressTracker() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun updateState(newState: PlaybackState) {
        currentState = newState
        mainHandler.post { onStateChanged?.invoke(newState) }
    }
}
