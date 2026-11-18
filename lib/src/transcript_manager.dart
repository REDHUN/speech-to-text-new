import 'models/transcription_segment.dart';
import 'sentence_formatter.dart';

/// Dart-side transcript accumulator and overlap reconciliation manager.
class TranscriptManager {
  final SentenceFormatter formatter;

  final List<TranscriptionSegment> _confirmedSegments = [];
  String _currentPartial = '';
  int _sessionStartTimeMs = 0;

  TranscriptManager({SentenceFormatter? formatter})
      : formatter = formatter ?? const SentenceFormatter();

  void reset() {
    _confirmedSegments.clear();
    _currentPartial = '';
    _sessionStartTimeMs = 0;
  }

  void setSessionStartTime(int timestampMs) {
    _sessionStartTimeMs = timestampMs;
  }

  void updatePartial(String text) {
    _currentPartial = text.trim();
  }

  TranscriptionSegment? confirmSegment(String rawText, int endTimeMs) {
    final trimmed = rawText.trim();
    if (trimmed.isEmpty) return null;

    final deduplicated = deduplicateOverlap(trimmed);
    if (deduplicated.isEmpty) return null;

    final formatted = formatter.formatText(deduplicated, isFinal: true);
    if (formatted.isEmpty) return null;

    final start = _sessionStartTimeMs;
    final end = (endTimeMs >= start) ? endTimeMs : start + 1000;

    final segment = TranscriptionSegment(
      text: formatted,
      startTimeMs: start,
      endTimeMs: end,
      confidence: 1.0,
      isFinal: true,
    );

    _confirmedSegments.add(segment);
    _currentPartial = '';
    _sessionStartTimeMs = end;
    return segment;
  }

  /// Suffix-prefix reconciliation: strips words from the beginning of [incoming]
  /// that duplicate the end of the last confirmed segment.
  String deduplicateOverlap(String incoming) {
    if (_confirmedSegments.isEmpty) return incoming;

    final lastSegment = _confirmedSegments.last.text;
    final lastWords = _cleanWords(lastSegment);
    final incomingWords = _cleanWords(incoming);
    final rawIncomingWords = incoming.split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();

    if (lastWords.isEmpty || incomingWords.isEmpty) return incoming;

    final maxOverlap = [6, lastWords.length, incomingWords.length].reduce((a, b) => a < b ? a : b);

    for (var k = maxOverlap; k >= 1; k--) {
      final tail = lastWords.sublist(lastWords.length - k);
      final head = incomingWords.sublist(0, k);

      var matches = true;
      for (var i = 0; i < k; i++) {
        if (tail[i] != head[i]) {
          matches = false;
          break;
        }
      }

      if (matches) {
        final remaining = rawIncomingWords.sublist(k);
        return remaining.join(' ');
      }
    }

    return incoming;
  }

  List<String> _cleanWords(String text) {
    return text
        .toLowerCase()
        .replaceAll(RegExp(r'[^a-z0-9\s]'), '')
        .split(RegExp(r'\s+'))
        .where((w) => w.isNotEmpty)
        .toList();
  }

  String getConfirmedText() {
    return _confirmedSegments.map((s) => s.text).join(' ');
  }

  String getCurrentPartial() => _currentPartial;

  List<TranscriptionSegment> getSegments() => List.unmodifiable(_confirmedSegments);

  String getFormattedFullText({int silenceDurationMs = 0}) {
    final confirmed = getConfirmedText();
    if (_currentPartial.isEmpty) {
      return confirmed;
    }

    final dedupPartial = deduplicateOverlap(_currentPartial);
    if (dedupPartial.isEmpty) return confirmed;

    final formattedPartial = formatter.formatText(
      dedupPartial,
      isFinal: false,
      silenceDurationMs: silenceDurationMs,
    );

    return confirmed.isEmpty ? formattedPartial : '$confirmed $formattedPartial';
  }
}
