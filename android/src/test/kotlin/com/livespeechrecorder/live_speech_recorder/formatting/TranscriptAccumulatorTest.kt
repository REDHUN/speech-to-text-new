package com.livespeechrecorder.live_speech_recorder.formatting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptAccumulatorTest {

    private val formatter = SentenceFormatter(enablePunctuation = true, enableCapitalization = true)
    private val accumulator = TranscriptAccumulator(formatter)

    @Test
    fun testDeduplicationAcrossSessionRestarts() {
        accumulator.confirmSegment("I want to explain how computers work", endTimeMs = 3000)
        assertEquals("I want to explain how computers work.", accumulator.getConfirmedText())

        accumulator.beginOverlapWindow()
        val incoming = "computers work in schools and hospitals"
        assertEquals("in schools and hospitals", accumulator.deduplicateOverlap(incoming))

        accumulator.confirmSegment(incoming, endTimeMs = 6000)
        assertEquals(
            "I want to explain how computers work. In schools and hospitals.",
            accumulator.getConfirmedText()
        )
    }

    @Test
    fun testWordsAreKeptWithinOneRecognitionSession() {
        accumulator.confirmSegment("we should ask what it is", endTimeMs = 1000)
        accumulator.confirmSegment("it is a very old idea", endTimeMs = 2000)
        assertTrue(accumulator.getConfirmedText().endsWith("It is a very old idea."))
    }

    @Test
    fun testOverlapWindowClosesAfterOneSegment() {
        accumulator.confirmSegment("the first part", endTimeMs = 1000)
        accumulator.beginOverlapWindow()
        accumulator.confirmSegment("part two begins", endTimeMs = 2000)

        assertEquals("Two begins.", accumulator.getSegments().last().text)
        assertEquals("begins here", accumulator.deduplicateOverlap("begins here"))
    }

    @Test
    fun testFullyDuplicatedSegmentClearsPartial() {
        accumulator.confirmSegment("hello world", endTimeMs = 1000)
        accumulator.beginOverlapWindow()
        accumulator.updatePartial("hello world")
        assertNull(accumulator.confirmSegment("hello world", endTimeMs = 2000))
        assertEquals("", accumulator.getCurrentPartial())
    }

    @Test
    fun testLivePartialMerging() {
        accumulator.confirmSegment("Artificial intelligence is fascinating", endTimeMs = 2500)
        accumulator.updatePartial("it helps us solve problems")

        val live = accumulator.getFormattedFullText()
        assertTrue(live.startsWith("Artificial intelligence is fascinating."))
        assertTrue(live.contains("It helps us solve problems"))
        assertFalse(live.endsWith("."))
    }

    @Test
    fun testLongPauseStartsParagraph() {
        accumulator.confirmSegment("first thought", endTimeMs = 1000)
        accumulator.notePause(4000)
        accumulator.confirmSegment("second thought", endTimeMs = 6000)
        assertEquals("First thought.\n\nSecond thought.", accumulator.getConfirmedText())
    }

    @Test
    fun testSegmentsCanBeReadIncrementally() {
        accumulator.confirmSegment("one", endTimeMs = 1000, confidence = 0.5)
        accumulator.confirmSegment("two", endTimeMs = 2000)

        val added = accumulator.getSegmentsFrom(1)
        assertEquals(1, added.size)
        assertEquals("Two.", added[0].text)
        assertNull(added[0].confidence)
        assertEquals(0.5, accumulator.getSegments()[0].confidence)
    }
}
