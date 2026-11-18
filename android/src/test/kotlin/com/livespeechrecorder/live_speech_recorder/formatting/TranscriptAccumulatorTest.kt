package com.livespeechrecorder.live_speech_recorder.formatting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptAccumulatorTest {

    private val formatter = SentenceFormatter(enablePunctuation = true, enableCapitalization = true)
    private val accumulator = TranscriptAccumulator(formatter)

    @Test
    fun testDeduplicationAcrossSessionRestarts() {
        // Initial segment
        accumulator.setSessionStartTime(0)
        accumulator.confirmSegment("I want to explain how computers work", endTimeMs = 3000)

        assertEquals("I want to explain how computers work.", accumulator.getConfirmedText())

        // Recognizer restarts and repeats last two words: "computers work in schools"
        val incoming = "computers work in schools and hospitals"
        val deduped = accumulator.deduplicateOverlap(incoming)

        assertEquals("in schools and hospitals", deduped)

        accumulator.confirmSegment(incoming, endTimeMs = 6000)
        val full = accumulator.getConfirmedText()

        assertTrue(full.contains("how computers work."))
        assertTrue(full.contains("In schools and hospitals."))
        // Ensure "computers work" was not duplicated in the confirmed stream
        val occurrences = "computers work".toRegex().findAll(full.lowercase()).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun testLivePartialMerging() {
        accumulator.setSessionStartTime(0)
        accumulator.confirmSegment("Artificial intelligence is fascinating", endTimeMs = 2500)
        accumulator.updatePartial("it helps us solve problems")

        val liveFormatted = accumulator.getFormattedFullText()
        assertTrue(liveFormatted.startsWith("Artificial intelligence is fascinating."))
        assertTrue(liveFormatted.contains("It helps us solve problems"))
    }
}
