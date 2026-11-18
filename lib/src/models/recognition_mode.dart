/// Speech recognition operating mode.
enum RecognitionMode {
  /// Strict offline-only recognition.
  /// Throws an error if on-device speech recognition models are unavailable.
  /// Never uploads student audio to network servers.
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
