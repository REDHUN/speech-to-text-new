/// A single segment of recognized speech with timestamps.
class TranscriptionSegment {
  /// Confirmed text content of this segment.
  final String text;

  /// Start position in the recorded audio, in milliseconds.
  final int startTimeMs;

  /// Position in the recorded audio when this segment was confirmed, in milliseconds.
  final int endTimeMs;

  /// Recognizer confidence from 0.0 to 1.0, or null if none was provided.
  final double? confidence;

  /// Whether this segment is confirmed/finalized.
  final bool isFinal;

  /// Whether a long pause came before this segment.
  final bool startsParagraph;

  const TranscriptionSegment({
    required this.text,
    required this.startTimeMs,
    required this.endTimeMs,
    this.confidence,
    this.isFinal = true,
    this.startsParagraph = false,
  });

  Duration get startDuration => Duration(milliseconds: startTimeMs);
  Duration get endDuration => Duration(milliseconds: endTimeMs);

  factory TranscriptionSegment.fromMap(Map<dynamic, dynamic> map) {
    return TranscriptionSegment(
      text: map['text'] as String? ?? '',
      startTimeMs: (map['startTimeMs'] as num?)?.toInt() ?? 0,
      endTimeMs: (map['endTimeMs'] as num?)?.toInt() ?? 0,
      confidence: (map['confidence'] as num?)?.toDouble(),
      isFinal: map['isFinal'] as bool? ?? true,
      startsParagraph: map['startsParagraph'] as bool? ?? false,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'text': text,
      'startTimeMs': startTimeMs,
      'endTimeMs': endTimeMs,
      'confidence': confidence,
      'isFinal': isFinal,
      'startsParagraph': startsParagraph,
    };
  }

  @override
  String toString() =>
      'TranscriptionSegment(text: "$text", start: ${startTimeMs}ms, end: ${endTimeMs}ms, isFinal: $isFinal)';
}
