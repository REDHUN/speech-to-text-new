package com.livespeechrecorder.live_speech_recorder.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @Test
    fun testWavHeaderAndDataIntegrity() {
        val tempFile = File.createTempFile("test_audio", ".wav")
        tempFile.deleteOnExit()

        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16

        val writer = WavFileWriter(
            file = tempFile,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample
        )

        writer.open()

        // 1 second of audio at 16kHz 16-bit mono = 32000 bytes
        val testPcm = ByteArray(32000)
        writer.write(testPcm, 0, testPcm.size)

        val durationMs = writer.close()
        assertEquals(1000L, durationMs)

        // Read and verify 44-byte WAV header
        val raf = RandomAccessFile(tempFile, "r")
        val headerBytes = ByteArray(44)
        raf.readFully(headerBytes)
        raf.close()

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

        // "RIFF"
        val riff = String(headerBytes, 0, 4)
        assertEquals("RIFF", riff)

        val totalFileSize = buffer.getInt(4)
        assertEquals(32000 + 36, totalFileSize)

        // "WAVE"
        val wave = String(headerBytes, 8, 4)
        assertEquals("WAVE", wave)

        // "fmt "
        val fmt = String(headerBytes, 12, 4)
        assertEquals("fmt ", fmt)

        val audioFormat = buffer.getShort(20).toInt()
        assertEquals(1, audioFormat) // PCM = 1

        val numChannels = buffer.getShort(22).toInt()
        assertEquals(channels, numChannels)

        val sRate = buffer.getInt(24)
        assertEquals(sampleRate, sRate)

        val byteRate = buffer.getInt(28)
        assertEquals(sampleRate * channels * 2, byteRate)

        // "data"
        val dataTag = String(headerBytes, 36, 4)
        assertEquals("data", dataTag)

        val dataSize = buffer.getInt(40)
        assertEquals(32000, dataSize)

        tempFile.delete()
    }
}
