package com.livespeechrecorder.live_speech_recorder.recognition

import com.livespeechrecorder.live_speech_recorder.audio.CaptureListener
import com.livespeechrecorder.live_speech_recorder.audio.CaptureStartResult
import com.livespeechrecorder.live_speech_recorder.audio.PcmAudioDistributor
import com.livespeechrecorder.live_speech_recorder.audio.PcmCaptureSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class RecognitionSessionManagerTest {

    private class Delayed(val dueAt: Long, val task: () -> Unit) {
        var cancelled = false
    }

    private inner class FakeCapture(val distributor: PcmAudioDistributor) : PcmCaptureSource {
        override var listener: CaptureListener? = null
        var isOpen = false

        override fun start(): CaptureStartResult {
            if (captureFailure != null) return captureFailure!!
            isOpen = true
            starts++
            return CaptureStartResult(true)
        }

        override fun stop() {
            isOpen = false
        }

        fun feed(bytes: Int) = distributor.dispatch(ByteArray(bytes), bytes)
    }

    private class FakeBackend : SpeechRecognitionBackend {
        var listener: BackendListener? = null
        var sessionStarts = 0
        var finishes = 0
        var released = false
        var expectsFinalResult = false
        var lastPreRoll: ByteArray? = null
        val audioBytes = java.util.concurrent.atomic.AtomicLong()

        override fun initialize(listener: BackendListener) {
            this.listener = listener
        }

        override fun startSession(preRollProvider: () -> ByteArray?) {
            sessionStarts++
            lastPreRoll = preRollProvider()
        }

        override fun writePcm(buffer: ByteArray, offset: Int, length: Int) {
            audioBytes.addAndGet(length.toLong())
        }

        override fun finishSession(): Boolean {
            finishes++
            return expectsFinalResult
        }

        override fun release() {
            released = true
        }
    }

    private inner class FakePlatform : SessionPlatform {
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val delayed = mutableListOf<Delayed>()
        var now = 0L
        var foregroundActive = false

        override fun runOnMain(task: () -> Unit) {
            queue.add(task)
        }

        override fun runOnMainDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = Delayed(now + delayMs, task)
            delayed += entry
            return { entry.cancelled = true }
        }

        override fun runInBackground(task: () -> Unit) {
            queue.add(task)
        }

        override fun hasRecordPermission() = permissionGranted

        override fun startForegroundNotice(title: String, text: String): Boolean {
            foregroundActive = true
            return true
        }

        override fun stopForegroundNotice() {
            foregroundActive = false
        }

        override fun createCapture(sampleRate: Int, distributor: PcmAudioDistributor) =
            FakeCapture(distributor).also { capture = it }

        override fun createBackend(config: RecognitionConfig): SpeechRecognitionBackend? =
            if (backendAvailable) FakeBackend().also { backend = it } else null
    }

    private var permissionGranted = true
    private var backendAvailable = true
    private var captureFailure: CaptureStartResult? = null
    private var starts = 0
    private var capture: FakeCapture? = null
    private var backend: FakeBackend? = null

    private val platform = FakePlatform()
    private val manager = RecognitionSessionManager(platform)
    private val states = mutableListOf<String>()
    private val errors = mutableListOf<Triple<String, String, Boolean>>()
    private val updates = mutableListOf<TranscriptUpdate>()
    private val file: File = File.createTempFile("session", ".wav").apply { deleteOnExit() }

    init {
        manager.onStateChanged = { states += it }
        manager.onError = { code, message, fatal -> errors += Triple(code, message, fatal) }
        manager.onTranscriptionUpdate = { updates += it }
    }

    /** Runs queued work, allowing for the distributor's real coroutine thread. */
    private fun settle() {
        var idleSince = System.nanoTime()
        while (System.nanoTime() - idleSince < 30_000_000L) {
            val task = platform.queue.poll()
            if (task == null) {
                Thread.sleep(1)
            } else {
                task()
                idleSince = System.nanoTime()
            }
        }
    }

    private fun advance(ms: Long) {
        platform.now += ms
        while (true) {
            val due = platform.delayed.firstOrNull { !it.cancelled && it.dueAt <= platform.now } ?: break
            platform.delayed.remove(due)
            due.task()
            settle()
        }
    }

    private fun pendingDelayCount() = platform.delayed.count { !it.cancelled }

    private fun startRecording(): StartOutcome {
        var outcome: StartOutcome? = null
        manager.start(file) { outcome = it }
        settle()
        return outcome!!
    }

    private fun stopRecording(): RecordingSummary? {
        var summary: RecordingSummary? = null
        var called = false
        manager.stop {
            summary = it
            called = true
        }
        settle()
        assertTrue(called)
        return summary
    }

    @Test
    fun startReportsMicrophoneFailureAndRollsBack() {
        captureFailure = CaptureStartResult(false, "MIC_BUSY", "busy")

        val outcome = startRecording()

        assertEquals("MIC_BUSY", outcome.errorCode)
        assertFalse(manager.isActive)
        assertEquals("IDLE", manager.wireState)
        assertFalse(states.contains("RECORDING"))
        assertFalse(platform.foregroundActive)
        assertTrue(file.exists(), "A failed start must not touch an existing file")
        assertTrue(backend!!.released)
    }

    @Test
    fun startRequiresPermission() {
        permissionGranted = false
        assertEquals("PERMISSION_DENIED", startRecording().errorCode)
        assertNull(capture)
    }

    @Test
    fun secondStartIsRejectedAndFirstRecordingSurvives() {
        assertEquals(file.absolutePath, startRecording().audioPath)
        capture!!.feed(3200)

        val other = File(file.parentFile, file.name + ".other")
        var second: StartOutcome? = null
        manager.start(other) { second = it }
        settle()

        assertEquals("ALREADY_RECORDING", second!!.errorCode)
        assertEquals(file.absolutePath, stopRecording()!!.audioPath)
        assertFalse(other.exists())
    }

    @Test
    fun commandsDuringStartAreQueuedInOrder() {
        val order = mutableListOf<String>()
        manager.start(file) { order += "start" }
        manager.pause { order += "pause:$it" }
        manager.resume { order += "resume:$it" }
        manager.stop { order += "stop" }
        settle()

        assertEquals(listOf("start", "pause:true", "resume:true", "stop"), order)
        assertEquals("STOPPED", manager.wireState)
    }

    @Test
    fun stopFinalizesFileAndRepeatedStopReturnsSameResult() {
        startRecording()
        capture!!.feed(16000)
        settle()

        val first = stopRecording()!!
        assertEquals(500L, first.durationMs)
        assertEquals(16044L, first.fileSizeBytes)
        assertNull(first.failureCode)
        assertEquals(first, stopRecording())
        assertFalse(capture!!.isOpen)
        assertFalse(platform.foregroundActive)
    }

    @Test
    fun stopWithoutRecordingReturnsNull() {
        assertNull(stopRecording())
    }

    @Test
    fun finalRecognizerResultIsIncludedBeforeStopCompletes() {
        startRecording()
        val recognizer = backend!!
        recognizer.expectsFinalResult = true
        recognizer.listener!!.onPartialResult("hello wor")

        var summary: RecordingSummary? = null
        manager.stop { summary = it }
        settle()
        assertNull(summary)
        assertEquals(1, recognizer.finishes)

        recognizer.listener!!.onSessionCompleted("hello world", 0.9)
        settle()

        assertEquals("Hello world.", summary!!.transcription)
        assertTrue(updates.last().isFinal)
    }

    @Test
    fun stopFallsBackToPartialWhenFinalResultNeverArrives() {
        startRecording()
        backend!!.expectsFinalResult = true
        backend!!.listener!!.onPartialResult("spoken words")

        var summary: RecordingSummary? = null
        manager.stop { summary = it }
        settle()
        assertNull(summary)

        advance(2_000)

        assertEquals("Spoken words.", summary!!.transcription)
    }

    @Test
    fun pauseClosesMicrophoneAndResumeReopensIt() {
        startRecording()
        val source = capture!!
        assertTrue(source.isOpen)

        var paused = false
        manager.pause { paused = it }
        settle()
        assertTrue(paused)
        assertFalse(source.isOpen)
        assertEquals("PAUSED", manager.wireState)

        var resumed = false
        manager.resume { resumed = it }
        settle()
        assertTrue(resumed)
        assertTrue(source.isOpen)
        assertEquals(2, starts)
        assertEquals(2, backend!!.sessionStarts)
        assertEquals("RECORDING", manager.wireState)
    }

    @Test
    fun pauseWhenNotRecordingReportsFalse() {
        var result = true
        manager.pause { result = it }
        settle()
        assertFalse(result)
    }

    @Test
    fun cancelDeletesFileAndRecorderCanStartAgainWithTranscription() {
        startRecording()
        capture!!.feed(3200)
        settle()

        var cancelled = false
        manager.cancel { cancelled = it }
        settle()
        assertTrue(cancelled)
        assertFalse(file.exists())
        assertEquals("IDLE", manager.wireState)

        startRecording()
        capture!!.feed(3200)
        settle()
        assertEquals(3200L, backend!!.audioBytes.get())
        assertEquals(100L, stopRecording()!!.durationMs)
    }

    @Test
    fun cancelAfterStopKeepsFinishedFile() {
        startRecording()
        capture!!.feed(3200)
        settle()
        stopRecording()

        var cancelled = true
        manager.cancel { cancelled = it }
        settle()
        assertFalse(cancelled)
        assertTrue(file.exists())
    }

    @Test
    fun releaseFinalizesActiveRecording() {
        startRecording()
        capture!!.feed(3200)
        settle()

        var released = false
        manager.release { released = true }
        settle()

        assertTrue(released)
        assertTrue(file.exists())
        assertEquals(3244L, file.length())
        assertFalse(manager.isActive)
    }

    @Test
    fun fatalCaptureErrorEndsRecordingAndKeepsAudio() {
        startRecording()
        capture!!.feed(3200)
        settle()

        capture!!.listener!!.onCaptureFatal("CAPTURE_FAILED", "gone")
        settle()

        assertEquals("ERROR", manager.wireState)
        assertTrue(errors.any { it.first == "CAPTURE_FAILED" && it.third })
        val summary = stopRecording()!!
        assertEquals("CAPTURE_FAILED", summary.failureCode)
        assertEquals(100L, summary.durationMs)
    }

    @Test
    fun partialIsKeptWhenRecognizerFails() {
        startRecording()
        val listener = backend!!.listener!!
        listener.onPartialResult("keep these words")
        listener.onError(BackendFailure("SERVER_ERROR", "down", isRecoverable = true))
        settle()

        assertEquals(1, manager.segmentsFrom(0).size)
        assertEquals("Keep these words.", manager.segmentsFrom(0)[0].text)
    }

    @Test
    fun recoverableFailuresBackOffAndReportOnce() {
        startRecording()
        val recognizer = backend!!
        val failure = BackendFailure("NETWORK_ERROR", "offline", isRecoverable = true)

        recognizer.listener!!.onError(failure)
        advance(299)
        assertEquals(1, recognizer.sessionStarts)
        advance(1)
        assertEquals(2, recognizer.sessionStarts)

        recognizer.listener!!.onError(failure)
        advance(599)
        assertEquals(2, recognizer.sessionStarts)
        advance(1)
        assertEquals(3, recognizer.sessionStarts)

        assertEquals(1, errors.count { it.first == "NETWORK_ERROR" })
    }

    @Test
    fun routineSilenceIsNotReportedAsAnError() {
        startRecording()
        backend!!.listener!!.onError(BackendFailure("NO_MATCH", "none", isRecoverable = true, isRoutine = true))
        advance(150)

        assertTrue(errors.isEmpty())
        assertEquals(2, backend!!.sessionStarts)
    }

    @Test
    fun unrecoverableFailureKeepsRecordingWithoutRestart() {
        startRecording()
        backend!!.listener!!.onError(BackendFailure("LANGUAGE_NOT_SUPPORTED", "no", isRecoverable = false))
        advance(60_000)

        assertEquals(1, backend!!.sessionStarts)
        assertEquals("RECORDING", manager.wireState)
        assertFalse(errors.single().third)
    }

    @Test
    fun pendingRestartIsCancelledByStop() {
        startRecording()
        val recognizer = backend!!
        recognizer.listener!!.onSessionCompleted("done", null)
        stopRecording()
        advance(1_000)
        assertEquals(1, recognizer.sessionStarts)
    }

    @Test
    fun restartReplaysRecentAudioToCoverTheGap() {
        startRecording()
        capture!!.feed(3200)
        settle()

        backend!!.listener!!.onSessionCompleted(null, null)
        advance(150)

        assertEquals(3200, backend!!.lastPreRoll!!.size)
    }

    @Test
    fun missingRecognizerStillRecordsAudio() {
        backendAvailable = false
        assertNotNull(startRecording().audioPath)
        capture!!.feed(3200)
        settle()

        assertTrue(errors.any { it.first == "TRANSCRIPTION_UNAVAILABLE" && !it.third })
        assertEquals(100L, stopRecording()!!.durationMs)
    }

    @Test
    fun durationComesFromRecordedAudioAndUsesOneTimer() {
        val durations = mutableListOf<Long>()
        manager.onDurationUpdate = { durations += it }
        startRecording()
        capture!!.feed(32000)
        settle()
        advance(250)

        assertEquals(1000L, durations.last())
        assertEquals(1, pendingDelayCount())
    }
}
