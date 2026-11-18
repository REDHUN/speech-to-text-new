/// Audio recording lifecycle state.
enum RecordingState {
  /// `initialize()` has not been called.
  uninitialized,

  /// Ready to record.
  idle,

  /// The microphone is open and audio is being saved.
  recording,

  /// The recording is paused and the microphone is closed.
  paused,

  /// The recording finished normally and its file is complete.
  stopped,

  /// The recording ended because of a fatal error. Audio captured so far is
  /// saved, and `stop()` returns its result.
  error;

  static RecordingState fromWireString(String? value) {
    switch (value?.toUpperCase()) {
      case 'RECORDING':
        return RecordingState.recording;
      case 'PAUSED':
        return RecordingState.paused;
      case 'STOPPED':
        return RecordingState.stopped;
      case 'IDLE':
        return RecordingState.idle;
      case 'ERROR':
        return RecordingState.error;
      case 'UNINITIALIZED':
      default:
        return RecordingState.uninitialized;
    }
  }
}
