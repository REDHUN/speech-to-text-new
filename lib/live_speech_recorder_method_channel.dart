import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'live_speech_recorder_platform_interface.dart';
import 'src/models/playback_progress.dart';
import 'src/models/playback_state.dart';
import 'src/models/recognition_capability.dart';
import 'src/models/recognition_mode.dart';
import 'src/models/recording_result.dart';
import 'src/models/recording_state.dart';
import 'src/models/saved_recording.dart';
import 'src/models/speech_recorder_exception.dart';
import 'src/models/transcription_result.dart';
import 'src/models/transcription_segment.dart';

/// Rebuilds complete transcripts from the incremental native updates.
///
/// Applying the same update twice has no further effect, because a broadcast
/// stream runs its mapping once per listener.
class _TranscriptAssembler {
  final List<TranscriptionSegment> _segments = [];
  final StringBuffer _confirmed = StringBuffer();
  List<TranscriptionSegment> _segmentsSnapshot = const [];
  String _confirmedText = '';

  TranscriptionResult apply(Map<dynamic, dynamic> event) {
    if (event['isReset'] == true) {
      _segments.clear();
      _confirmed.clear();
      _segmentsSnapshot = const [];
      _confirmedText = '';
    }

    final startIndex = (event['segmentStartIndex'] as num?)?.toInt() ?? 0;
    final incoming = event['segments'] as List<dynamic>? ?? const [];
    var changed = false;
    for (var i = 0; i < incoming.length; i++) {
      if (startIndex + i < _segments.length) continue;
      final segment = TranscriptionSegment.fromMap(incoming[i] as Map<dynamic, dynamic>);
      if (_segments.isNotEmpty) {
        _confirmed.write(segment.startsParagraph ? '\n\n' : ' ');
      }
      _confirmed.write(segment.text);
      _segments.add(segment);
      changed = true;
    }
    if (changed) {
      _segmentsSnapshot = List.unmodifiable(_segments);
      _confirmedText = _confirmed.toString();
    }

    final formattedPartial = event['formattedPartial'] as String? ?? '';
    final String fullText;
    if (formattedPartial.isEmpty) {
      fullText = _confirmedText;
    } else if (_confirmedText.isEmpty) {
      fullText = formattedPartial;
    } else {
      fullText = '$_confirmedText $formattedPartial';
    }

    return TranscriptionResult(
      fullText: fullText,
      partialText: event['partialText'] as String? ?? '',
      confirmedText: _confirmedText,
      isFinal: event['isFinal'] as bool? ?? false,
      segments: _segmentsSnapshot,
    );
  }
}

/// An implementation of [LiveSpeechRecorderPlatform] that uses method channels and event channels.
class MethodChannelLiveSpeechRecorder extends LiveSpeechRecorderPlatform {
  @visibleForTesting
  final methodChannel = const MethodChannel('com.livespeechrecorder/methods');

  final _transcriptionEventChannel =
      const EventChannel('com.livespeechrecorder/transcription');
  final _recordingStateEventChannel =
      const EventChannel('com.livespeechrecorder/recording_state');
  final _playbackStateEventChannel =
      const EventChannel('com.livespeechrecorder/playback_state');
  final _playbackPositionEventChannel =
      const EventChannel('com.livespeechrecorder/playback_position');
  final _amplitudeEventChannel =
      const EventChannel('com.livespeechrecorder/amplitude');
  final _durationEventChannel =
      const EventChannel('com.livespeechrecorder/duration');
  final _errorEventChannel =
      const EventChannel('com.livespeechrecorder/errors');

  final _transcriptAssembler = _TranscriptAssembler();

  Stream<TranscriptionResult>? _transcriptionStream;
  Stream<RecordingState>? _recordingStateStream;
  Stream<PlaybackState>? _playbackStateStream;
  Stream<PlaybackProgress>? _playbackProgressStream;
  Stream<double>? _amplitudeStream;
  Stream<Duration>? _durationStream;
  Stream<SpeechRecorderException>? _errorStream;

  /// Invokes a native method, reporting failures as [SpeechRecorderException].
  Future<T?> _invoke<T>(String method, [Map<String, Object?>? arguments]) async {
    try {
      return await methodChannel.invokeMethod<T>(method, arguments);
    } on PlatformException catch (error) {
      throw SpeechRecorderException.fromPlatformException(error);
    }
  }

  Future<bool> _invokeBool(String method, [Map<String, Object?>? arguments]) async {
    return await _invoke<bool>(method, arguments) ?? false;
  }

  @override
  Future<String?> getPlatformVersion() => _invoke<String>('getPlatformVersion');

  @override
  Future<RecognitionCapability> checkCapabilities() async {
    final map = await _invoke<Map<dynamic, dynamic>>('checkCapabilities');
    return RecognitionCapability.fromMap(map ?? const {});
  }

  @override
  Future<bool> initialize({
    String language = 'en-IN',
    int sampleRate = 16000,
    RecognitionMode recognitionMode = RecognitionMode.automatic,
    bool enablePunctuation = true,
    bool enableSegmented = true,
    bool enableBackgroundRecording = true,
    String notificationTitle = 'Recording audio',
    String notificationText = 'Microphone is in use',
  }) {
    return _invokeBool('initialize', {
      'language': language,
      'sampleRate': sampleRate,
      'recognitionMode': recognitionMode.toWireString(),
      'enablePunctuation': enablePunctuation,
      'enableSegmented': enableSegmented,
      'enableBackgroundRecording': enableBackgroundRecording,
      'notificationTitle': notificationTitle,
      'notificationText': notificationText,
    });
  }

  @override
  Future<String> start({String? outputPath}) async {
    final path = await _invoke<String>('start', {'outputPath': outputPath});
    if (path == null) {
      throw const SpeechRecorderException(
        code: 'START_FAILED',
        message: 'Native start method returned no file path.',
      );
    }
    return path;
  }

  @override
  Future<bool> pause() => _invokeBool('pause');

  @override
  Future<bool> resume() => _invokeBool('resume');

  @override
  Future<RecordingResult> stop() async {
    final map = await _invoke<Map<dynamic, dynamic>>('stop');
    if (map == null) {
      throw const SpeechRecorderException(
        code: 'STOP_FAILED',
        message: 'Native stop method returned no result.',
      );
    }
    return RecordingResult.fromMap(map);
  }

  @override
  Future<bool> cancel() => _invokeBool('cancel');

  @override
  Future<RecordingState> getRecordingState() async {
    return RecordingState.fromWireString(await _invoke<String>('getRecordingState'));
  }

  @override
  Future<PlaybackState> getPlaybackState() async {
    return PlaybackState.fromWireString(await _invoke<String>('getPlaybackState'));
  }

  @override
  Future<List<SavedRecording>> listRecordings() async {
    final items = await _invoke<List<dynamic>>('listRecordings') ?? const [];
    return items
        .map((item) => SavedRecording.fromMap(item as Map<dynamic, dynamic>))
        .toList(growable: false);
  }

  @override
  Future<bool> deleteRecording(String path) => _invokeBool('deleteRecording', {'path': path});

  @override
  Future<bool> play({String? filePath}) => _invokeBool('play', {'filePath': filePath});

  @override
  Future<bool> pausePlayback() => _invokeBool('pausePlayback');

  @override
  Future<bool> resumePlayback() => _invokeBool('resumePlayback');

  @override
  Future<bool> stopPlayback() => _invokeBool('stopPlayback');

  @override
  Future<bool> seekTo(Duration position) {
    return _invokeBool('seekTo', {'positionMs': position.inMilliseconds});
  }

  @override
  Future<bool> setPlaybackSpeed(double speed) => _invokeBool('setPlaybackSpeed', {'speed': speed});

  @override
  Future<Duration> getPlaybackPosition() async {
    return Duration(milliseconds: await _invoke<int>('getPlaybackPosition') ?? 0);
  }

  @override
  Future<Duration> getPlaybackDuration() async {
    return Duration(milliseconds: await _invoke<int>('getPlaybackDuration') ?? 0);
  }

  @override
  Future<bool> dispose() => _invokeBool('dispose');

  @override
  Stream<TranscriptionResult> get transcriptionStream {
    return _transcriptionStream ??= _transcriptionEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .map((event) => _transcriptAssembler.apply(event as Map<dynamic, dynamic>));
  }

  @override
  Stream<RecordingState> get recordingStateStream {
    return _recordingStateStream ??= _recordingStateEventChannel
        .receiveBroadcastStream()
        .map((event) => RecordingState.fromWireString(event?.toString()));
  }

  @override
  Stream<PlaybackState> get playbackStateStream {
    return _playbackStateStream ??= _playbackStateEventChannel
        .receiveBroadcastStream()
        .map((event) => PlaybackState.fromWireString(event?.toString()));
  }

  @override
  Stream<PlaybackProgress> get playbackProgressStream {
    return _playbackProgressStream ??= _playbackPositionEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .map((event) => PlaybackProgress.fromMap(event as Map<dynamic, dynamic>));
  }

  @override
  Stream<double> get amplitudeStream {
    return _amplitudeStream ??= _amplitudeEventChannel
        .receiveBroadcastStream()
        .map((event) => (event as num?)?.toDouble() ?? 0.0);
  }

  @override
  Stream<Duration> get durationStream {
    return _durationStream ??= _durationEventChannel
        .receiveBroadcastStream()
        .map((event) => Duration(milliseconds: (event as num?)?.toInt() ?? 0));
  }

  @override
  Stream<SpeechRecorderException> get errorStream {
    return _errorStream ??= _errorEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .map((event) => SpeechRecorderException.fromMap(event as Map<dynamic, dynamic>));
  }
}
