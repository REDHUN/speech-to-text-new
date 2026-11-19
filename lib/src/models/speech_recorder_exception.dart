import 'package:flutter/services.dart';

/// The error type used by every method and by the error stream.
///
/// Common codes:
/// - Starting: `NOT_INITIALIZED`, `ALREADY_RECORDING`, `PERMISSION_DENIED`,
///   `MIC_BUSY`, `AUDIO_RECORD_INIT_FAILED`, `FILE_OPEN_FAILED`.
/// - Fatal during recording: `CAPTURE_FAILED`, `DISK_WRITE_FAILED`, `DISK_TOO_SLOW`.
/// - Recording warnings: `MIC_SILENCED`, `MIC_INPUT_LOST`, `MIC_RESTORED`,
///   `AUDIO_ROUTE_CHANGED`, `CAPTURE_RESTARTED`, `BACKGROUND_RECORDING_UNAVAILABLE`,
///   `BLUETOOTH_MIC_UNAVAILABLE`.
/// - Transcription: `TRANSCRIPTION_UNAVAILABLE`, `OFFLINE_UNAVAILABLE`,
///   `AUDIO_INJECTION_UNSUPPORTED`, `NETWORK_ERROR`, `LANGUAGE_NOT_SUPPORTED`,
///   `LANGUAGE_UNAVAILABLE`.
class SpeechRecorderException implements Exception {
  /// Stable error identifier.
  final String code;

  /// Human-readable explanation of the error.
  final String message;

  /// Whether this error ended the recording. Non-fatal errors leave audio
  /// recording running, even if transcription has stopped.
  final bool isFatal;

  /// Optional underlying native error details.
  final Object? details;

  const SpeechRecorderException({
    required this.code,
    required this.message,
    this.isFatal = false,
    this.details,
  });

  factory SpeechRecorderException.fromMap(Map<dynamic, dynamic> map) {
    return SpeechRecorderException(
      code: map['code'] as String? ?? 'UNKNOWN_ERROR',
      message: map['message'] as String? ?? 'An unknown error occurred.',
      isFatal: map['isFatal'] as bool? ?? false,
      details: map['details'],
    );
  }

  factory SpeechRecorderException.fromPlatformException(PlatformException error) {
    return SpeechRecorderException(
      code: error.code,
      message: error.message ?? error.code,
      details: error.details,
    );
  }

  @override
  String toString() =>
      'SpeechRecorderException(code: "$code", message: "$message", isFatal: $isFatal)';
}
