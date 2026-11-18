package com.livespeechrecorder.live_speech_recorder.formatting

import java.util.Locale

data class TimedSegment(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val confidence: Double = 1.0,
    val isFinal: Boolean = true
)

/**
 * Manages the three representations of transcription:
 * 1. Confirmed text (historical finalized sentences).
 * 2. Current partial text (real-time hypothesis of current speech segment).
 * 3. Formatted full text (intelligently formatted coherent transcript).
 *
 * Implements segment reconciliation and suffix-prefix deduplication across recognition session restarts.
 */
class TranscriptAccumulator(
    private val formatter: SentenceFormatter
) {
    private val confirmedSegments = mutableListOf<TimedSegment>()
    private var currentPartial: String = ""
    private var sessionStartTimeMs: Long = 0L

    val lock = Any()

    /**
     * Resets all accumulator state.
     */
    fun reset() {
        synchronized(lock) {
            confirmedSegments.clear()
            currentPartial = ""
            sessionStartTimeMs = 0L
        }
    }

    fun setSessionStartTime(timestampMs: Long) {
        synchronized(lock) {
            sessionStartTimeMs = timestampMs
        }
    }

    /**
     * Updates the active live partial hypothesis.
     */
    fun updatePartial(partialText: String) {
        synchronized(lock) {
            currentPartial = partialText.trim()
        }
    }

    /**
     * Confirms and appends a segment, performing n-gram deduplication against the trailing words
     * of previous segments.
     */
    fun confirmSegment(rawText: String, endTimeMs: Long): TimedSegment? {
        synchronized(lock) {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null

            // Deduplicate against tail of confirmed segments
            val deduplicated = deduplicateOverlap(trimmed)
            if (deduplicated.isEmpty()) return null

            val formatted = formatter.formatText(deduplicated, isFinal = true)
            if (formatted.isEmpty()) return null

            val start = sessionStartTimeMs
            val end = if (endTimeMs >= start) endTimeMs else start + 1000L

            val segment = TimedSegment(
                text = formatted,
                startTimeMs = start,
                endTimeMs = end,
                confidence = 1.0,
                isFinal = true
            )

            confirmedSegments.add(segment)
            currentPartial = ""
            // Advance session start time for next segment
            sessionStartTimeMs = end

            return segment
        }
    }

    /**
     * Suffix-prefix word reconciliation algorithm.
     * Detects if the incoming segment begins with words that already ended the previous confirmed segment.
     */
    fun deduplicateOverlap(incoming: String): String {
        if (confirmedSegments.isEmpty()) return incoming

        val lastSegment = confirmedSegments.lastOrNull()?.text ?: return incoming
        val lastWords = cleanWords(lastSegment)
        val incomingWords = cleanWords(incoming)
        val rawIncomingWords = incoming.split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (lastWords.isEmpty() || incomingWords.isEmpty()) return incoming

        val maxOverlap = minOf(6, lastWords.size, incomingWords.size)

        for (k in maxOverlap downTo 1) {
            val tail = lastWords.takeLast(k)
            val head = incomingWords.take(k)

            if (tail == head) {
                // Found overlap of length k words! Strip the first k words from incoming
                val remainingWords = rawIncomingWords.drop(k)
                return remainingWords.joinToString(" ")
            }
        }

        return incoming
    }

    private fun cleanWords(text: String): List<String> {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    /**
     * Returns the combined confirmed text so far.
     */
    fun getConfirmedText(): String {
        synchronized(lock) {
            return confirmedSegments.joinToString(" ") { it.text }
        }
    }

    /**
     * Returns current live partial text.
     */
    fun getCurrentPartial(): String {
        synchronized(lock) {
            return currentPartial
        }
    }

    /**
     * Returns all confirmed segments with timestamps.
     */
    fun getSegments(): List<TimedSegment> {
        synchronized(lock) {
            return ArrayList(confirmedSegments)
        }
    }

    /**
     * Generates the combined full formatted text:
     * Confirmed text + formatted live partial hypothesis.
     */
    fun getFormattedFullText(silenceDurationMs: Long = 0L): String {
        synchronized(lock) {
            val confirmed = getConfirmedText()
            if (currentPartial.isEmpty()) {
                return confirmed
            }

            // Deduplicate partial against last confirmed segment
            val dedupPartial = deduplicateOverlap(currentPartial)
            if (dedupPartial.isEmpty()) return confirmed

            val formattedPartial = formatter.formatText(dedupPartial, isFinal = false, silenceDurationMs = silenceDurationMs)

            return if (confirmed.isEmpty()) {
                formattedPartial
            } else {
                "$confirmed $formattedPartial"
            }
        }
    }
}
