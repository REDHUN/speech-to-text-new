/// Audio playback lifecycle state.
enum PlaybackState {
  idle,
  playing,
  paused,
  completed,
  error;

  static PlaybackState fromWireString(String? value) {
    switch (value?.toUpperCase()) {
      case 'PLAYING':
        return PlaybackState.playing;
      case 'PAUSED':
        return PlaybackState.paused;
      case 'COMPLETED':
        return PlaybackState.completed;
      case 'ERROR':
        return PlaybackState.error;
      case 'IDLE':
      default:
        return PlaybackState.idle;
    }
  }
}
