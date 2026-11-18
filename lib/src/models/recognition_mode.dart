/// Speech recognition operating mode.
enum RecognitionMode {
  /// Strict offline-only recognition.
  /// If the on-device model for the language is missing, audio is still
  /// recorded and `OFFLINE_UNAVAILABLE` is sent through the error stream.
  /// Never uses a network recognizer.
  offlineOnly,

  /// Permits online cloud recognition if network is available.
  onlineAllowed,

  /// Automatically prefers offline on-device recognition if available,
  /// falling back to network recognition only when needed.
  automatic;

  String toWireString() {
    switch (this) {
      case RecognitionMode.offlineOnly:
        return 'offlineOnly';
      case RecognitionMode.onlineAllowed:
        return 'onlineAllowed';
      case RecognitionMode.automatic:
        return 'automatic';
    }
  }

  static RecognitionMode fromWireString(String? value) {
    switch (value) {
      case 'offlineOnly':
        return RecognitionMode.offlineOnly;
      case 'onlineAllowed':
        return RecognitionMode.onlineAllowed;
      case 'automatic':
      default:
        return RecognitionMode.automatic;
    }
  }
}
