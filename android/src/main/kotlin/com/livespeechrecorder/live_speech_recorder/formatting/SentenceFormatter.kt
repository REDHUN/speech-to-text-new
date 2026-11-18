package com.livespeechrecorder.live_speech_recorder.formatting

import java.util.Locale

/**
 * Conservative sentence formatting for recognizer output.
 *
 * Text the recognizer already punctuated keeps its punctuation. Unpunctuated English text gets
 * capitalization and sentence endings, and is only split where a boundary is very likely.
 * Text in other languages is returned unchanged because these rules are English-specific.
 */
class SentenceFormatter(
    private val enablePunctuation: Boolean = true,
    private val enableCapitalization: Boolean = true,
    language: String = "en"
) {
    private val appliesEnglishRules = language.lowercase(Locale.ROOT).startsWith("en")

    private val questionAuxiliaries = hashSetOf(
        "is", "are", "am", "was", "were", "do", "does", "did", "can", "could",
        "would", "should", "will", "shall", "has", "have", "had", "may", "might"
    )

    private val subjectWords = hashSetOf(
        "i", "you", "he", "she", "it", "we", "they", "this", "that", "these", "those", "there"
    )

    private val determiners = hashSetOf("the", "a", "an", "my", "your", "his", "her", "its", "our", "their")

    // "do the work" and "have a nice day" are usually commands, not questions.
    private val auxiliariesAllowingDeterminer = hashSetOf(
        "is", "are", "was", "were", "does", "did", "can", "could", "would", "should", "will"
    )

    private val howQualifiers = hashSetOf("many", "much", "long", "far", "often", "old")

    private val sentenceTransitions = listOf(
        listOf("however"), listOf("therefore"), listOf("furthermore"), listOf("moreover"),
        listOf("firstly"), listOf("secondly"), listOf("thirdly"), listOf("finally"),
        listOf("consequently"), listOf("meanwhile"), listOf("in", "addition"),
        listOf("for", "example"), listOf("for", "instance"),
        listOf("on", "the", "other", "hand"), listOf("as", "a", "result")
    )

    private val introductoryPhrases = sentenceTransitions + listOf(listOf("today"))

    // A boundary cannot follow these words: the clause is still open.
    private val openClauseWords = hashSetOf(
        "a", "an", "the", "and", "but", "or", "so", "then", "that", "if", "because", "when",
        "while", "as", "is", "are", "was", "were", "be", "to", "of", "in", "on", "for", "with",
        "what", "which", "who", "think", "know", "believe", "said", "say", "feel", "hope"
    )

    private val pronounClauseVerbs = hashSetOf(
        "am", "was", "have", "had", "will", "would", "can", "could", "want", "think", "believe", "feel"
    )

    private val commonAcronyms = hashMapOf(
        "ai" to "AI", "ml" to "ML", "cpu" to "CPU", "ram" to "RAM", "gpu" to "GPU",
        "os" to "OS", "pc" to "PC", "usb" to "USB", "api" to "API", "url" to "URL",
        "html" to "HTML", "css" to "CSS", "sdk" to "SDK", "sql" to "SQL", "iot" to "IoT",
        "nlp" to "NLP", "llm" to "LLM", "http" to "HTTP", "https" to "HTTPS",
        "json" to "JSON", "xml" to "XML"
    )

    private val properNouns = hashSetOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "april", "june", "july", "september", "october", "november", "december",
        "android", "google", "flutter", "kotlin", "java", "python",
        "india", "america", "britain", "england", "china", "japan",
        "germany", "france", "canada", "australia", "delhi", "mumbai", "bangalore", "london",
        "rahul", "priya", "amit", "rohan", "neha", "alex", "john", "sarah", "michael", "david"
    )

    // These are also ordinary words, so they are capitalized only in a date-like context.
    private val ambiguousMonths = hashSetOf("march", "may", "august")
    private val monthContextWords = hashSetOf("in", "of", "since", "until", "from", "by", "during", "last", "next")

    /**
     * @param rawText Unformatted speech text.
     * @param isFinal Whether the text is finalized and should receive closing punctuation.
     * @param silenceDurationMs Current silence length, used to close a live partial early.
     */
    fun formatText(rawText: String, isFinal: Boolean, silenceDurationMs: Long = 0L): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        if (!appliesEnglishRules) return trimmed

        val words = trimmed.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.any { it.last() in PUNCTUATION_MARKS }) {
            return formatPunctuatedWords(words, isFinal)
        }

        val sentences = splitIntoSentences(words)
        return sentences.mapIndexed { index, sentence ->
            val closed = index < sentences.lastIndex || isFinal ||
                (silenceDurationMs >= PARTIAL_CLOSE_SILENCE_MS && sentence.size >= MIN_SENTENCE_WORDS)
            formatSingleSentence(sentence, closed)
        }.joinToString(" ")
    }

    private fun formatPunctuatedWords(words: List<String>, isFinal: Boolean): String {
        var startsSentence = true
        val result = words.mapIndexed { index, word ->
            val core = word.trimEnd { it in PUNCTUATION_MARKS }
            val trailing = word.substring(core.length)
            var cased = applyWordCase(core, normalize(words.getOrNull(index - 1)), normalize(words.getOrNull(index + 1)))
            if (startsSentence) cased = capitalizeFirst(cased)
            startsSentence = trailing.any { it in SENTENCE_ENDINGS }
            cased + trailing
        }.joinToString(" ")

        val needsEnding = enablePunctuation && isFinal && result.last() !in SENTENCE_ENDINGS
        return if (needsEnding) result.trimEnd(',', ';', ':') + "." else result
    }

    private fun splitIntoSentences(words: List<String>): List<List<String>> {
        val sentences = mutableListOf<List<String>>()
        var current = mutableListOf<String>()

        for (index in words.indices) {
            current.add(words[index])
            if (current.size >= MIN_SENTENCE_WORDS && startsNewSentenceAfter(words, index)) {
                sentences.add(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) sentences.add(current)
        return sentences
    }

    private fun startsNewSentenceAfter(words: List<String>, index: Int): Boolean {
        if (index + 1 >= words.size || normalize(words[index]) in openClauseWords) return false

        val next = normalize(words[index + 1])
        val afterNext = normalize(words.getOrNull(index + 2))
        if (next == "i" && afterNext in pronounClauseVerbs) return true

        val transition = sentenceTransitions.firstOrNull { phraseMatches(words, index + 1, it) } ?: return false
        // "in addition to that" continues the sentence.
        return normalize(words.getOrNull(index + 1 + transition.size)) != "to"
    }

    private fun phraseMatches(words: List<String>, start: Int, phrase: List<String>): Boolean {
        if (start + phrase.size > words.size) return false
        return phrase.indices.all { normalize(words[start + it]) == phrase[it] }
    }

    /**
     * Formats one sentence's words.
     */
    fun formatSingleSentence(words: List<String>, isFinal: Boolean): String {
        if (words.isEmpty()) return ""

        val introLength = introductoryPhrases
            .firstOrNull { phraseMatches(words, 0, it) }
            ?.size
            ?.takeIf { words.size > it + 1 }
            ?: 0

        val result = words.mapIndexed { index, word ->
            var cleaned = applyWordCase(
                word.trim().trimEnd { it in PUNCTUATION_MARKS },
                normalize(words.getOrNull(index - 1)),
                normalize(words.getOrNull(index + 1))
            )
            if (index == 0) cleaned = capitalizeFirst(cleaned)
            if (enablePunctuation && index == introLength - 1) cleaned += ","
            cleaned
        }.filter { it.isNotEmpty() }.joinToString(" ")

        if (!enablePunctuation || !isFinal || result.isEmpty()) return result
        return result + if (isQuestion(words)) "?" else "."
    }

    private fun isQuestion(words: List<String>): Boolean {
        val first = normalize(words.first())
        val second = normalize(words.getOrNull(1))
        return when (first) {
            "who", "what", "which", "whose", "whom" ->
                second.isNotEmpty() && second !in subjectWords && second !in determiners && second != "to"
            "when", "where", "why" -> second in questionAuxiliaries
            "how" -> second in questionAuxiliaries || second in howQualifiers
            in questionAuxiliaries -> words.size >= 3 && (
                second in subjectWords ||
                    (first in auxiliariesAllowingDeterminer && second in determiners)
                )
            else -> false
        }
    }

    private fun applyWordCase(word: String, previous: String, next: String): String {
        val lower = word.lowercase(Locale.ROOT)
        commonAcronyms[lower]?.let { return it }
        return when {
            lower == "i" -> "I"
            lower.startsWith("i'") || lower.startsWith("i’") -> "I" + word.substring(1)
            lower in properNouns -> capitalizeFirst(word)
            lower in ambiguousMonths && isDateContext(previous, next) -> capitalizeFirst(word)
            else -> word
        }
    }

    private fun isDateContext(previous: String, next: String): Boolean =
        previous in monthContextWords || previous.firstOrNull()?.isDigit() == true ||
            next.firstOrNull()?.isDigit() == true

    private fun capitalizeFirst(word: String): String {
        if (!enableCapitalization) return word
        return word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun normalize(word: String?): String =
        word?.trimEnd { it in PUNCTUATION_MARKS }?.lowercase(Locale.ROOT).orEmpty()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val PUNCTUATION_MARKS = ".,?!;:"
        const val SENTENCE_ENDINGS = ".?!"
        const val MIN_SENTENCE_WORDS = 4
        const val PARTIAL_CLOSE_SILENCE_MS = 1200L
    }
}
