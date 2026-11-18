/// Diagnostic capabilities of the host Android device for speech recognition.
class RecognitionCapability {
  /// Whether Android SpeechRecognizer is installed and available.
  final bool hasSpeechRecognizer;

  /// Whether modern audio source injection (RecognizerIntent.EXTRA_AUDIO_SOURCE)
  /// is supported (Android 13 / API 33+).
  final bool supportsAudioSourceInjection;

  /// Whether offline on-device speech recognition is supported (Android 12 / API 31+).
  final bool supportsOfflineRecognition;

  /// List of package names of installed speech recognition services.
  final List<String> availableServices;

  /// Package name of the default/Google speech service if present.
  final String? defaultServicePackage;

  /// Recommended recognition mode for this specific device.
  final String recommendedMode;

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
        const [];

    return RecognitionCapability(
      hasSpeechRecognizer: map['hasSpeechRecognizer'] as bool? ?? false,
      supportsAudioSourceInjection:
          map['supportsAudioSourceInjection'] as bool? ?? false,
      supportsOfflineRecognition:
          map['supportsOfflineRecognition'] as bool? ?? false,
      availableServices: services,
      defaultServicePackage: map['defaultServicePackage'] as String?,
      recommendedMode: map['recommendedMode'] as String? ?? 'automatic',
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'hasSpeechRecognizer': hasSpeechRecognizer,
      'supportsAudioSourceInjection': supportsAudioSourceInjection,
      'supportsOfflineRecognition': supportsOfflineRecognition,
      'availableServices': availableServices,
      'defaultServicePackage': defaultServicePackage,
      'recommendedMode': recommendedMode,
    };
  }

  @override
  String toString() =>
      'RecognitionCapability(hasASR: $hasSpeechRecognizer, injectAudio: $supportsAudioSourceInjection, offline: $supportsOfflineRecognition, mode: $recommendedMode)';
}
