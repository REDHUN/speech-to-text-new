/// A recording stored in the plugin's recordings directory.
class SavedRecording {
  final String path;
  final int sizeBytes;
  final Duration duration;
  final DateTime modifiedAt;

  const SavedRecording({
    required this.path,
    required this.sizeBytes,
    required this.duration,
    required this.modifiedAt,
  });

  factory SavedRecording.fromMap(Map<dynamic, dynamic> map) {
    return SavedRecording(
      path: map['path'] as String? ?? '',
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      duration: Duration(milliseconds: (map['durationMs'] as num?)?.toInt() ?? 0),
      modifiedAt: DateTime.fromMillisecondsSinceEpoch(
        (map['modifiedAtMs'] as num?)?.toInt() ?? 0,
      ),
    );
  }

  @override
  String toString() => 'SavedRecording(path: "$path", duration: $duration, size: $sizeBytes bytes)';
}
