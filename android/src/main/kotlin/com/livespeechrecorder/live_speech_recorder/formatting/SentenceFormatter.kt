package com.livespeechrecorder.live_speech_recorder.formatting

import java.util.Locale

/**
 * Intelligent sentence boundary detection, punctuation restoration, and capitalization engine.
 *
 * Designed for educational long-form answers, essays, and explanations.
 */
class SentenceFormatter(
    private val enablePunctuation: Boolean = true,
    private val enableCapitalization: Boolean = true
) {

    private val interrogativeWhWords = hashSetOf(
        "who", "what", "where", "when", "why", "how", "which", "whose", "whom"
    )

    private val questionAuxiliaries = hashSetOf(
        "is", "are", "am", "was", "were",
        "do", "does", "did",
        "can", "could", "would", "should", "will", "shall",
        "has", "have", "had", "may", "might"
    )

    private val transitionPhrases = hashSetOf(
        "however", "therefore", "furthermore", "moreover", "in addition",
        "for example", "for instance", "firstly", "secondly", "thirdly",
        "finally", "on the other hand", "as a result", "consequently", "meanwhile", "today"
    )

    private val commonAcronyms = hashMapOf(
        "ai" to "AI",
        "ml" to "ML",
        "cpu" to "CPU",
        "ram" to "RAM",
        "gpu" to "GPU",
        "os" to "OS",
        "pc" to "PC",
        "usb" to "USB",
        "api" to "API",
        "url" to "URL",
        "html" to "HTML",
        "css" to "CSS",
        "sdk" to "SDK",
        "sql" to "SQL",
        "iot" to "IoT",
        "nlp" to "NLP",
        "llm" to "LLM",
        "http" to "HTTP",
        "https" to "HTTPS",
        "json" to "JSON",
        "xml" to "XML"
    )

    private val properNouns = hashSetOf(
        // Days
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        // Months
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
        // Tech platforms
        "android", "google", "flutter", "kotlin", "java", "python",
        // Countries & regions
        "india", "america", "britain", "england", "china", "japan",
        "germany", "france", "canada", "australia", "delhi", "mumbai", "bangalore", "london", "new york",
        // Names common in student contexts
        "rahul", "priya", "amit", "rohan", "neha", "alex", "john", "sarah", "michael", "david"
    )

    /**
     * Formats a raw unpunctuated text stream into sentences with proper capitalization and punctuation.
     *
     * @param rawText Unformatted speech text.
     * @param isFinal Whether the segment is finalized (adds closing punctuation).
     * @param silenceDurationMs Silence duration in ms from VAD.
     */
    fun formatText(rawText: String, isFinal: Boolean, silenceDurationMs: Long = 0L): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""

        val sentences = mutableListOf<List<String>>()
        var currentSentenceWords = mutableListOf<String>()

        for (i in words.indices) {
            val word = words[i]
            currentSentenceWords.add(word)

            val hasTrailingPunctuation = word.endsWith(".") || word.endsWith("?") || word.endsWith("!")
            val nextWord = if (i + 1 < words.size) words[i + 1].lowercase(Locale.ROOT) else null
            val nextNextWord = if (i + 2 < words.size) words[i + 2].lowercase(Locale.ROOT) else null

            var shouldBreak = hasTrailingPunctuation

            if (!shouldBreak && nextWord != null && currentSentenceWords.size >= 4) {
                if (transitionPhrases.contains(nextWord)) {
                    shouldBreak = true
                } else if (nextWord == "i" && nextNextWord != null &&
                    hashSetOf("am", "was", "have", "had", "will", "would", "can", "could", "want", "think", "believe", "feel").contains(nextNextWord)
                ) {
                    shouldBreak = true
                } else if (interrogativeWhWords.contains(nextWord)) {
                    val lastWordLower = currentSentenceWords.last().lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
                    val subordinateVerbs = hashSetOf(
                        "explain", "know", "understand", "learn", "see", "show", "tell",
                        "discuss", "hear", "wonder", "ask", "asked", "wondering"
                    )
                    if (!subordinateVerbs.contains(lastWordLower)) {
                        shouldBreak = true
                    }
                }
            }

            if (shouldBreak) {
                sentences.add(currentSentenceWords)
                currentSentenceWords = mutableListOf()
            }
        }

        if (currentSentenceWords.isNotEmpty()) {
            sentences.add(currentSentenceWords)
        }

        val formattedSentences = mutableListOf<String>()

        for (sIndex in sentences.indices) {
            val sentenceWords = sentences[sIndex]
            val isLastSentence = (sIndex == sentences.size - 1)
            val formatAsFinal = (!isLastSentence) || isFinal || (silenceDurationMs >= 1200L && sentenceWords.size >= 4)

            val formatted = formatSingleSentence(sentenceWords, formatAsFinal)
            if (formatted.isNotEmpty()) {
                formattedSentences.add(formatted)
            }
        }

        val separator = if (silenceDurationMs >= 3500L) "\n\n" else " "
        return formattedSentences.joinToString(separator)
    }

    /**
     * Formats an individual sentence list of words.
     */
    fun formatSingleSentence(words: List<String>, isFinal: Boolean): String {
        if (words.isEmpty()) return ""

        val transformedWords = mutableListOf<String>()

        for (i in words.indices) {
            val rawWord = words[i]
            var cleanWord = rawWord.trim().replace(Regex("[.,?!;:]+$"), "")
            val lower = cleanWord.lowercase(Locale.ROOT)

            // 1. Acronym check
            val acronym = commonAcronyms[lower]
            if (acronym != null) {
                cleanWord = acronym
            }
            // 2. Standalone "I" and contractions
            else if (lower == "i") {
                cleanWord = "I"
            } else if (lower.startsWith("i'") || lower.startsWith("i’")) {
                cleanWord = "I" + cleanWord.substring(1)
            }
            // 3. Proper noun check
            else if (properNouns.contains(lower)) {
                cleanWord = cleanWord.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
            // 4. First word capitalization
            if (enableCapitalization && i == 0) {
                cleanWord = cleanWord.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }

            // 5. Add comma after introductory transition word at the start of sentence
            if (enablePunctuation && i == 0 && transitionPhrases.contains(lower) && words.size > 2) {
                cleanWord += ","
            }

            transformedWords.add(cleanWord)
        }

        var result = transformedWords.joinToString(" ")

        // 6. Sentence ending punctuation
        if (enablePunctuation && isFinal) {
            val firstWordLower = words.first().lowercase(Locale.ROOT)
            val endsWithPunct = result.endsWith(".") || result.endsWith("?") || result.endsWith("!")

            if (!endsWithPunct) {
                val isQuestion = interrogativeWhWords.contains(firstWordLower) ||
                        (questionAuxiliaries.contains(firstWordLower) && words.size >= 3)
                result += if (isQuestion) "?" else "."
            }
        }

        return result
    }
}
