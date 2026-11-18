/// Exception thrown by the speech recording and transcription subsystem.
class SpeechRecorderException implements Exception {
  /// Error identifier code (e.g. 'PERMISSION_DENIED', 'OFFLINE_UNAVAILABLE', 'TIMEOUT').
  final String code;

  /// Human-readable explanation of the error.
  final String message;

  /// Optional underlying native error details.
  final dynamic details;

  const SpeechRecorderException({
    required this.code,
    required this.message,
    this.details,
  });

  factory SpeechRecorderException.fromMap(Map<dynamic, dynamic> map) {
    return SpeechRecorderException(
      code: map['code'] as String? ?? 'UNKNOWN_ERROR',
      message: map['message'] as String? ?? 'An unknown error occurred.',
      details: map['details'],
    );
  }

  @override
  String toString() => 'SpeechRecorderException(code: "$code", message: "$message")';
}
