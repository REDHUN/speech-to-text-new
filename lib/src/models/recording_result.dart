/// Result metadata emitted when audio recording and transcription are finalized.
class RecordingResult {
  /// Local file system path to the saved WAV audio file.
  final String audioPath;

  /// Complete final formatted transcription.
  final String transcription;

  /// Total duration of recorded audio.
  final Duration duration;

  /// Size of the saved WAV file in bytes.
  final int fileSizeBytes;

  /// Audio sample rate (default 16000 Hz).
  final int sampleRate;

  /// Audio channels (1 for mono).
  final int channels;

  /// The error code that ended the recording early, or null after a normal stop.
  ///
  /// Audio captured before the failure is still saved at [audioPath].
  final String? failureCode;

  const RecordingResult({
    required this.audioPath,
    required this.transcription,
    required this.duration,
    required this.fileSizeBytes,
    required this.sampleRate,
    required this.channels,
    this.failureCode,
  });

  /// Whether any audio was captured.
  bool get hasAudio => duration > Duration.zero;

  factory RecordingResult.fromMap(Map<dynamic, dynamic> map) {
    final durationMs = (map['durationMs'] as num?)?.toInt() ?? 0;
    return RecordingResult(
      audioPath: map['audioPath'] as String? ?? '',
      transcription: map['transcription'] as String? ?? '',
      duration: Duration(milliseconds: durationMs),
      fileSizeBytes: (map['fileSizeBytes'] as num?)?.toInt() ?? 0,
      sampleRate: (map['sampleRate'] as num?)?.toInt() ?? 16000,
      channels: (map['channels'] as num?)?.toInt() ?? 1,
      failureCode: map['failureCode'] as String?,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'audioPath': audioPath,
      'transcription': transcription,
      'durationMs': duration.inMilliseconds,
      'fileSizeBytes': fileSizeBytes,
      'sampleRate': sampleRate,
      'channels': channels,
      'failureCode': failureCode,
    };
  }

  @override
  String toString() =>
      'RecordingResult(path: "$audioPath", duration: $duration, size: $fileSizeBytes bytes, failure: $failureCode, text: "$transcription")';
}
