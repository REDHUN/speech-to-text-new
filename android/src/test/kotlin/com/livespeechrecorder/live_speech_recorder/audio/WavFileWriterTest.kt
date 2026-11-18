package com.livespeechrecorder.live_speech_recorder.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    private fun tempWav() = File.createTempFile("test_audio", ".wav").apply { deleteOnExit() }

    private fun header(file: File): ByteBuffer {
        val bytes = ByteArray(WavFileWriter.HEADER_BYTES)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test
    fun testWavHeaderAndDataIntegrity() {
        val file = tempWav()
        val writer = WavFileWriter(file, sampleRate = 16000, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.write(ByteArray(32000), 0, 32000)

        assertEquals(1000L, writer.close())

        val header = header(file)
        val bytes = header.array()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(32036, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(32000, header.getInt(40))
    }

    @Test
    fun testHeaderIsRefreshedDuringLongRecording() {
        val file = tempWav()
        val writer = WavFileWriter(file)
        writer.open()
        repeat(6) { writer.write(ByteArray(32000), 0, 32000) }

        // Without close(), this is what a killed process leaves behind.
        assertEquals(160000, header(file).getInt(40))
        writer.cancel()
    }

    @Test
    fun testRepairRecoversUnfinishedRecording() {
        val file = tempWav()
        val writer = WavFileWriter(file)
        writer.open()
        writer.write(ByteArray(16000), 0, 16000)

        assertEquals(500L, WavFileWriter.repairHeader(file))
        assertEquals(16000, header(file).getInt(40))
        assertEquals(16036, header(file).getInt(4))
        writer.cancel()
    }

    @Test
    fun testRepairRejectsNonWavFile() {
        val file = tempWav()
        file.writeBytes(ByteArray(100) { 7 })
        assertNull(WavFileWriter.repairHeader(file))
    }

    @Test
    fun testCancelDeletesInProgressFileOnly() {
        val active = tempWav()
        WavFileWriter(active).apply { open(); cancel() }
        assertFalse(active.exists())

        val finished = tempWav()
        WavFileWriter(finished).apply { open(); close(); cancel() }
        assertTrue(finished.exists())
    }

    @Test
    fun testSinkWritesQueuedAudioBeforeFinishing() {
        val file = tempWav()
        val sink = WavRecordingSink(WavFileWriter(file)) { _, _ -> }
        sink.open()
        repeat(10) { sink.onPcmAudio(ByteArray(3200), 3200) }

        assertEquals(1000L, sink.finish())
        assertEquals(32000, header(file).getInt(40))
    }

    @Test
    fun testSinkOpenFailureIsThrown() {
        val notADirectory = tempWav()
        val sink = WavRecordingSink(WavFileWriter(File(notADirectory, "audio.wav"))) { _, _ -> }
        assertThrows(IOException::class.java) { sink.open() }
    }

    @Test
    fun testSinkReportsOverflowOnce() {
        val failures = mutableListOf<String>()
        val sink = WavRecordingSink(WavFileWriter(tempWav()), capacityChunks = 1) { code, _ ->
            failures += code
        }
        // Not opened, so nothing drains the queue.
        repeat(3) { sink.onPcmAudio(ByteArray(10), 10) }

        assertEquals(listOf("DISK_TOO_SLOW"), failures)
    }

    private val silentChunk = ByteArray(3200)
    private val audioChunk = ByteArray(3200) { 1 }

    private fun SilentInputMonitor.feedSilence(chunks: Int) =
        (1..chunks).map { onAudio(silentChunk, silentChunk.size) }

    @Test
    fun testMonitorRequestsRestartAfterSustainedDigitalSilence() {
        val monitor = SilentInputMonitor(32000)

        val actions = monitor.feedSilence(15)

        assertEquals(List(14) { SilentInputMonitor.Action.NONE }, actions.dropLast(1))
        assertEquals(SilentInputMonitor.Action.RESTART, actions.last())
    }

    @Test
    fun testMonitorIgnoresQuietAudioThatIsNotDigitalSilence() {
        val monitor = SilentInputMonitor(32000)
        val quiet = ByteArray(3200).also { it[3198] = 1 }

        repeat(100) { assertEquals(SilentInputMonitor.Action.NONE, monitor.onAudio(quiet, quiet.size)) }
    }

    @Test
    fun testMonitorBacksOffAndReportsRecovery() {
        val monitor = SilentInputMonitor(32000)
        monitor.feedSilence(15)

        // The second restart needs twice as much silence.
        assertEquals(SilentInputMonitor.Action.NONE, monitor.feedSilence(29).last())
        assertEquals(SilentInputMonitor.Action.RESTART, monitor.feedSilence(1).last())
        assertEquals(2, monitor.restartsWithoutAudio)

        assertEquals(SilentInputMonitor.Action.RECOVERED, monitor.onAudio(audioChunk, audioChunk.size))
        assertEquals(0, monitor.restartsWithoutAudio)
        assertEquals(SilentInputMonitor.Action.NONE, monitor.onAudio(audioChunk, audioChunk.size))
    }

    @Test
    fun testRingBufferKeepsNewestBytesInOrder() {
        val ring = PcmRingBuffer(4)
        assertNull(ring.snapshot())
        ring.append(byteArrayOf(1, 2, 3), 3)
        ring.append(byteArrayOf(4, 5, 6), 3)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), ring.snapshot())
        ring.clear()
        assertNull(ring.snapshot())
    }
}
