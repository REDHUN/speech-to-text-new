import 'recognition_mode.dart';

/// Diagnostic capabilities of the host Android device for speech recognition.
class RecognitionCapability {
  /// Whether an Android speech recognition service is available.
  final bool hasSpeechRecognizer;

  /// Whether the device can attempt shared-microphone audio injection.
  ///
  /// Android cannot report whether a recognition service honors injected audio
  /// ahead of time. If it does not, recording continues and
  /// `AUDIO_INJECTION_UNSUPPORTED` is sent through the error stream.
  final bool supportsAudioSourceInjection;

  /// Whether on-device speech recognition is available.
  final bool supportsOfflineRecognition;

  /// List of package names of installed speech recognition services.
  final List<String> availableServices;

  /// Package name of the Google speech service, if one is present.
  final String? defaultServicePackage;

  /// Recommended recognition mode for this device.
  final RecognitionMode recommendedMode;

  const RecognitionCapability({
    required this.hasSpeechRecognizer,
    required this.supportsAudioSourceInjection,
    required this.supportsOfflineRecognition,
    required this.availableServices,
    this.defaultServicePackage,
    required this.recommendedMode,
  });

  factory RecognitionCapability.fromMap(Map<dynamic, dynamic> map) {
    final services = (map['availableServices'] as List<dynamic>?)
            ?.map((e) => e.toString())
            .toList() ??
        const <String>[];

    return RecognitionCapability(
      hasSpeechRecognizer: map['hasSpeechRecognizer'] as bool? ?? false,
      supportsAudioSourceInjection:
          map['supportsAudioSourceInjection'] as bool? ?? false,
      supportsOfflineRecognition:
          map['supportsOfflineRecognition'] as bool? ?? false,
      availableServices: services,
      defaultServicePackage: map['defaultServicePackage'] as String?,
      recommendedMode:
          RecognitionMode.fromWireString(map['recommendedMode'] as String?),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'hasSpeechRecognizer': hasSpeechRecognizer,
      'supportsAudioSourceInjection': supportsAudioSourceInjection,
      'supportsOfflineRecognition': supportsOfflineRecognition,
      'availableServices': availableServices,
      'defaultServicePackage': defaultServicePackage,
      'recommendedMode': recommendedMode.toWireString(),
    };
  }

  @override
  String toString() =>
      'RecognitionCapability(hasASR: $hasSpeechRecognizer, injectAudio: $supportsAudioSourceInjection, offline: $supportsOfflineRecognition, mode: ${recommendedMode.name})';
}
