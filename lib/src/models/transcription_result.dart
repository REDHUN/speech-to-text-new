import 'transcription_segment.dart';

/// Real-time transcription state event emitted during live speech recognition.
class TranscriptionResult {
  /// Complete intelligently formatted transcript combining historical confirmed text
  /// with live formatted partial preview.
  final String fullText;

  /// Active unconfirmed hypothesis of the currently spoken segment.
  final String partialText;

  /// Immutable history of finalized sentences from prior segments.
  final String confirmedText;

  /// True when this segment is fully finalized.
  final bool isFinal;

  /// Ordered list of confirmed segments with respective timestamps.
  final List<TranscriptionSegment> segments;

  const TranscriptionResult({
    required this.fullText,
    required this.partialText,
    required this.confirmedText,
    required this.isFinal,
    this.segments = const [],
  });

  factory TranscriptionResult.fromMap(Map<dynamic, dynamic> map) {
    final rawSegments = map['segments'] as List<dynamic>? ?? [];
    final parsedSegments = rawSegments
        .whereType<Map<dynamic, dynamic>>()
        .map((s) => TranscriptionSegment.fromMap(s))
        .toList();

    return TranscriptionResult(
      fullText: map['fullText'] as String? ?? '',
      partialText: map['partialText'] as String? ?? '',
      confirmedText: map['confirmedText'] as String? ?? '',
      isFinal: map['isFinal'] as bool? ?? false,
      segments: parsedSegments,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'fullText': fullText,
      'partialText': partialText,
      'confirmedText': confirmedText,
      'isFinal': isFinal,
      'segments': segments.map((s) => s.toMap()).toList(),
    };
  }

  @override
  String toString() =>
      'TranscriptionResult(fullText: "$fullText", partial: "$partialText", isFinal: $isFinal, segments: ${segments.length})';
}
