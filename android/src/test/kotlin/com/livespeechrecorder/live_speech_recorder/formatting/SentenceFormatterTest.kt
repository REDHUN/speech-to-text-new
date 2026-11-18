package com.livespeechrecorder.live_speech_recorder.formatting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceFormatterTest {

    private val formatter = SentenceFormatter(enablePunctuation = true, enableCapitalization = true)

    private fun format(text: String) = formatter.formatText(text, isFinal = true)

    @Test
    fun testCapitalizationAndPunctuation() {
        assertEquals(
            "My name is Rahul. I am studying computer science.",
            format("my name is rahul i am studying computer science")
        )
    }

    @Test
    fun testAcronymHandling() {
        assertEquals(
            "Today, we will learn about AI and ML using modern CPU architectures.",
            format("today we will learn about ai and ml using modern cpu architectures")
        )
    }

    @Test
    fun testQuestionDetection() {
        assertEquals("How does a computer process information?", format("how does a computer process information"))
        assertEquals("Do you understand this?", format("do you understand this"))
    }

    @Test
    fun testLongSentenceHandling() {
        val formatted = format(
            "computer is an electronic device that accepts data from the user processes the information and provides output"
        )
        assertTrue(formatted.startsWith("Computer is an electronic device"))
        assertTrue(formatted.endsWith("."))
    }

    @Test
    fun testTransitionComma() {
        assertEquals(
            "However, we must consider the environmental impact.",
            format("however we must consider the environmental impact")
        )
    }

    @Test
    fun testSubordinateClausesAreNotSplit() {
        assertEquals("This is the place where we live.", format("this is the place where we live"))
        assertEquals("I do not remember what happened.", format("i do not remember what happened"))
        assertEquals("I want to go home today.", format("i want to go home today"))
    }

    @Test
    fun testCommandsAreNotQuestions() {
        assertEquals("Have a nice day everyone.", format("have a nice day everyone"))
        assertEquals("Do the work before class.", format("do the work before class"))
    }

    @Test
    fun testAmbiguousMonthsNeedDateContext() {
        assertEquals("You may go to the market now.", format("you may go to the market now"))
        assertEquals("We met in May.", format("we met in may"))
    }

    @Test
    fun testMultiWordTransitions() {
        assertEquals(
            "This method works well. For example, it saves time.",
            format("this method works well for example it saves time")
        )
        assertEquals(
            "This one works well in addition to that.",
            format("this one works well in addition to that")
        )
    }

    @Test
    fun testRecognizerPunctuationIsPreserved() {
        assertEquals("Really? Yes, I think so, honestly!", format("Really? Yes, I think so, honestly!"))
        assertEquals("Dr. Smith teaches here.", format("Dr. Smith teaches here."))
    }

    @Test
    fun testPunctuationCanBeDisabledWithoutLosingRecognizerPunctuation() {
        val plain = SentenceFormatter(enablePunctuation = false)
        assertEquals("Hello there", plain.formatText("hello there", isFinal = true))
        assertEquals("Really? Yes", plain.formatText("Really? Yes", isFinal = true))
    }

    @Test
    fun testOtherLanguagesAreLeftUnchanged() {
        val hindi = SentenceFormatter(language = "hi-IN")
        assertEquals("नमस्ते दुनिया", hindi.formatText(" नमस्ते दुनिया ", isFinal = true))
    }
}
