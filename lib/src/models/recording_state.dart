/// Audio recording lifecycle state.
enum RecordingState {
  uninitialized,
  idle,
  recording,
  paused,
  stopped;

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
      case 'UNINITIALIZED':
      default:
        return RecordingState.uninitialized;
    }
  }
}
