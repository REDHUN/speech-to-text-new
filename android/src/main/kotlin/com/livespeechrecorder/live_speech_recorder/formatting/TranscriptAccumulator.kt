package com.livespeechrecorder.live_speech_recorder.formatting

import java.util.Locale

data class TimedSegment(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    /** Recognizer confidence from 0.0 to 1.0, or null when the recognizer gave none. */
    val confidence: Double? = null,
    val isFinal: Boolean = true,
    val startsParagraph: Boolean = false
)

/**
 * Holds confirmed segments and the live partial hypothesis. Thread-safe.
 *
 * Words are only deduplicated in the window after audio has been replayed into a new recognition
 * session, the one place recognizer output can legitimately overlap.
 */
class TranscriptAccumulator(
    private val formatter: SentenceFormatter
) {
    private val lock = Any()
    private val confirmedSegments = mutableListOf<TimedSegment>()
    private var currentPartial = ""
    private var sessionStartTimeMs = 0L
    private var overlapWindowOpen = false
    private var paragraphBeforeNext = false
    private var paragraphAfterNext = false

    fun reset() {
        synchronized(lock) {
            confirmedSegments.clear()
            currentPartial = ""
            sessionStartTimeMs = 0L
            overlapWindowOpen = false
            paragraphBeforeNext = false
            paragraphAfterNext = false
        }
    }

    fun setSessionStartTime(timestampMs: Long) {
        synchronized(lock) { sessionStartTimeMs = timestampMs }
    }

    /** Marks that the next recognizer output may repeat words already confirmed. */
    fun beginOverlapWindow() {
        synchronized(lock) { overlapWindowOpen = true }
    }

    /** Records a silence that ended, so a long one can begin a new paragraph. */
    fun notePause(silenceMs: Long) {
        if (silenceMs < PARAGRAPH_SILENCE_MS) return
        synchronized(lock) {
            // If speech before the pause is still unconfirmed, the break belongs after it.
            if (currentPartial.isEmpty()) paragraphBeforeNext = true else paragraphAfterNext = true
        }
    }

    fun updatePartial(partialText: String) {
        synchronized(lock) { currentPartial = partialText.trim() }
    }

    /**
     * Confirms a segment. Returns null when nothing new remained after deduplication.
     */
    fun confirmSegment(rawText: String, endTimeMs: Long, confidence: Double? = null): TimedSegment? {
        synchronized(lock) {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null

            val deduplicated = deduplicateLocked(trimmed)
            overlapWindowOpen = false
            currentPartial = ""
            if (deduplicated.isEmpty()) return null

            val formatted = formatter.formatText(deduplicated, isFinal = true)
            if (formatted.isEmpty()) return null

            val start = sessionStartTimeMs
            val end = maxOf(start, endTimeMs)
            val segment = TimedSegment(
                text = formatted,
                startTimeMs = start,
                endTimeMs = end,
                confidence = confidence,
                isFinal = true,
                startsParagraph = paragraphBeforeNext && confirmedSegments.isNotEmpty()
            )

            confirmedSegments.add(segment)
            sessionStartTimeMs = end
            paragraphBeforeNext = paragraphAfterNext
            paragraphAfterNext = false
            return segment
        }
    }

    /** Removes leading words of [incoming] that repeat the end of the last segment. */
    fun deduplicateOverlap(incoming: String): String {
        synchronized(lock) { return deduplicateLocked(incoming) }
    }

    private fun deduplicateLocked(incoming: String): String {
        if (!overlapWindowOpen) return incoming
        val lastSegment = confirmedSegments.lastOrNull()?.text ?: return incoming

        val lastWords = comparableWords(lastSegment)
        val rawWords = incoming.split(WHITESPACE).filter { it.isNotEmpty() }
        val comparable = rawWords.map(::comparableForm)
        val comparableIndexes = comparable.indices.filter { comparable[it].isNotEmpty() }
        val incomingWords = comparableIndexes.map { comparable[it] }

        val maxOverlap = minOf(MAX_OVERLAP_WORDS, lastWords.size, incomingWords.size)
        for (overlap in maxOverlap downTo 1) {
            if (lastWords.takeLast(overlap) == incomingWords.take(overlap)) {
                return rawWords.drop(comparableIndexes[overlap - 1] + 1).joinToString(" ")
            }
        }
        return incoming
    }

    private fun comparableWords(text: String): List<String> =
        text.split(WHITESPACE).map(::comparableForm).filter { it.isNotEmpty() }

    private fun comparableForm(word: String): String =
        word.lowercase(Locale.ROOT).replace(NON_WORD_CHARACTERS, "")

    fun getConfirmedText(): String {
        synchronized(lock) {
            val builder = StringBuilder()
            confirmedSegments.forEachIndexed { index, segment ->
                if (index > 0) builder.append(if (segment.startsParagraph) "\n\n" else " ")
                builder.append(segment.text)
            }
            return builder.toString()
        }
    }

    fun getCurrentPartial(): String {
        synchronized(lock) { return currentPartial }
    }

    fun getSegmentCount(): Int {
        synchronized(lock) { return confirmedSegments.size }
    }

    fun getSegments(): List<TimedSegment> = getSegmentsFrom(0)

    fun getSegmentsFrom(index: Int): List<TimedSegment> {
        synchronized(lock) {
            if (index >= confirmedSegments.size) return emptyList()
            return ArrayList(confirmedSegments.subList(index, confirmedSegments.size))
        }
    }

    /** Returns the live partial as it should appear after the confirmed text. */
    fun getFormattedPartial(silenceDurationMs: Long = 0L): String {
        synchronized(lock) {
            if (currentPartial.isEmpty()) return ""
            val partial = deduplicateLocked(currentPartial)
            if (partial.isEmpty()) return ""
            return formatter.formatText(partial, isFinal = false, silenceDurationMs = silenceDurationMs)
        }
    }

    fun getFormattedFullText(silenceDurationMs: Long = 0L): String {
        synchronized(lock) {
            val confirmed = getConfirmedText()
            val partial = getFormattedPartial(silenceDurationMs)
            return when {
                partial.isEmpty() -> confirmed
                confirmed.isEmpty() -> partial
                else -> "$confirmed $partial"
            }
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val NON_WORD_CHARACTERS = Regex("[^\\p{L}\\p{N}]")
        const val MAX_OVERLAP_WORDS = 6
        const val PARAGRAPH_SILENCE_MS = 3500L
    }
}
