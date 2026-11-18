package com.livespeechrecorder.live_speech_recorder.formatting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceFormatterTest {

    private val formatter = SentenceFormatter(enablePunctuation = true, enableCapitalization = true)

    @Test
    fun testCapitalizationAndPunctuation() {
        val raw = "my name is rahul i am studying computer science"
        val formatted = formatter.formatText(raw, isFinal = true)
        assertEquals("My name is Rahul. I am studying computer science.", formatted)
    }

    @Test
    fun testAcronymHandling() {
        val raw = "today we will learn about ai and ml using modern cpu architectures"
        val formatted = formatter.formatText(raw, isFinal = true)
        assertEquals("Today, we will learn about AI and ML using modern CPU architectures.", formatted)
    }

    @Test
    fun testQuestionDetection() {
        val raw = "how does a computer process information"
        val formatted = formatter.formatText(raw, isFinal = true)
        assertEquals("How does a computer process information?", formatted)
    }

    @Test
    fun testLongSentenceHandling() {
        val raw = "computer is an electronic device that accepts data from the user processes the information and provides output"
        val formatted = formatter.formatText(raw, isFinal = true)
        assertTrue(formatted.startsWith("Computer is an electronic device"))
        assertTrue(formatted.endsWith("."))
    }

    @Test
    fun testTransitionComma() {
        val raw = "however we must consider the environmental impact"
        val formatted = formatter.formatText(raw, isFinal = true)
        assertEquals("However, we must consider the environmental impact.", formatted)
    }
}
