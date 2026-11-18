/// The current position within the audio being played.
class PlaybackProgress {
  final Duration position;
  final Duration duration;

  const PlaybackProgress({required this.position, required this.duration});

  factory PlaybackProgress.fromMap(Map<dynamic, dynamic> map) {
    return PlaybackProgress(
      position: Duration(milliseconds: (map['positionMs'] as num?)?.toInt() ?? 0),
      duration: Duration(milliseconds: (map['durationMs'] as num?)?.toInt() ?? 0),
    );
  }

  @override
  String toString() => 'PlaybackProgress(position: $position, duration: $duration)';
}
