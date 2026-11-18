/// A single segment of recognized speech with timestamps.
class TranscriptionSegment {
  /// Confirmed text content of this segment.
  final String text;

  /// Start timestamp in milliseconds relative to the start of recording.
  final int startTimeMs;

  /// End timestamp in milliseconds relative to the start of recording.
  final int endTimeMs;

  /// Confidence score between 0.0 and 1.0.
  final double confidence;

  /// Whether this segment is confirmed/finalized.
  final bool isFinal;

  const TranscriptionSegment({
    required this.text,
    required this.startTimeMs,
    required this.endTimeMs,
    this.confidence = 1.0,
    this.isFinal = true,
  });

  Duration get startDuration => Duration(milliseconds: startTimeMs);
  Duration get endDuration => Duration(milliseconds: endTimeMs);

  factory TranscriptionSegment.fromMap(Map<dynamic, dynamic> map) {
    return TranscriptionSegment(
      text: map['text'] as String? ?? '',
      startTimeMs: (map['startTimeMs'] as num?)?.toInt() ?? 0,
      endTimeMs: (map['endTimeMs'] as num?)?.toInt() ?? 0,
      confidence: (map['confidence'] as num?)?.toDouble() ?? 1.0,
      isFinal: map['isFinal'] as bool? ?? true,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'text': text,
      'startTimeMs': startTimeMs,
      'endTimeMs': endTimeMs,
      'confidence': confidence,
      'isFinal': isFinal,
    };
  }

  @override
  String toString() =>
      'TranscriptionSegment(text: "$text", start: ${startTimeMs}ms, end: ${endTimeMs}ms, isFinal: $isFinal)';
}
