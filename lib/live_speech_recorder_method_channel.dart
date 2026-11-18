import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'live_speech_recorder_platform_interface.dart';
import 'src/models/playback_state.dart';
import 'src/models/recognition_capability.dart';
import 'src/models/recognition_mode.dart';
import 'src/models/recording_result.dart';
import 'src/models/recording_state.dart';
import 'src/models/speech_recorder_exception.dart';
import 'src/models/transcription_result.dart';

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
  final _amplitudeEventChannel =
      const EventChannel('com.livespeechrecorder/amplitude');
  final _durationEventChannel =
      const EventChannel('com.livespeechrecorder/duration');
  final _errorEventChannel =
      const EventChannel('com.livespeechrecorder/errors');

  Stream<TranscriptionResult>? _transcriptionStream;
  Stream<RecordingState>? _recordingStateStream;
  Stream<PlaybackState>? _playbackStateStream;
  Stream<double>? _amplitudeStream;
  Stream<Duration>? _durationStream;
  Stream<SpeechRecorderException>? _errorStream;

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  @override
  Future<RecognitionCapability> checkCapabilities() async {
    final map = await methodChannel.invokeMapMethod<dynamic, dynamic>('checkCapabilities');
    return RecognitionCapability.fromMap(map ?? const {});
  }

  @override
  Future<bool> initialize({
    String language = 'en-IN',
    int sampleRate = 16000,
    RecognitionMode recognitionMode = RecognitionMode.automatic,
    bool enablePunctuation = true,
    bool enableSegmented = true,
  }) async {
    final success = await methodChannel.invokeMethod<bool>('initialize', {
      'language': language,
      'sampleRate': sampleRate,
      'recognitionMode': recognitionMode.toWireString(),
      'enablePunctuation': enablePunctuation,
      'enableSegmented': enableSegmented,
    });
    return success ?? false;
  }

  @override
  Future<String> start({String? outputPath}) async {
    final path = await methodChannel.invokeMethod<String>('start', {
      'outputPath': outputPath,
    });
    if (path == null) {
      throw const SpeechRecorderException(
        code: 'START_FAILED',
        message: 'Native start method returned null file path.',
      );
    }
    return path;
  }

  @override
  Future<bool> pause() async {
    final res = await methodChannel.invokeMethod<bool>('pause');
    return res ?? false;
  }

  @override
  Future<bool> resume() async {
    final res = await methodChannel.invokeMethod<bool>('resume');
    return res ?? false;
  }

  @override
  Future<RecordingResult> stop() async {
    final map = await methodChannel.invokeMapMethod<dynamic, dynamic>('stop');
    if (map == null) {
      throw const SpeechRecorderException(
        code: 'STOP_FAILED',
        message: 'Native stop method returned null result map.',
      );
    }
    return RecordingResult.fromMap(map);
  }

  @override
  Future<bool> cancel() async {
    final res = await methodChannel.invokeMethod<bool>('cancel');
    return res ?? false;
  }

  @override
  Future<bool> play({String? filePath}) async {
    final res = await methodChannel.invokeMethod<bool>('play', {
      'filePath': filePath,
    });
    return res ?? false;
  }

  @override
  Future<bool> pausePlayback() async {
    final res = await methodChannel.invokeMethod<bool>('pausePlayback');
    return res ?? false;
  }

  @override
  Future<bool> resumePlayback() async {
    final res = await methodChannel.invokeMethod<bool>('resumePlayback');
    return res ?? false;
  }

  @override
  Future<bool> stopPlayback() async {
    final res = await methodChannel.invokeMethod<bool>('stopPlayback');
    return res ?? false;
  }

  @override
  Future<bool> seekTo(Duration position) async {
    final res = await methodChannel.invokeMethod<bool>('seekTo', {
      'positionMs': position.inMilliseconds,
    });
    return res ?? false;
  }

  @override
  Future<bool> setPlaybackSpeed(double speed) async {
    final res = await methodChannel.invokeMethod<bool>('setPlaybackSpeed', {
      'speed': speed,
    });
    return res ?? false;
  }

  @override
  Future<Duration> getPlaybackPosition() async {
    final posMs = await methodChannel.invokeMethod<int>('getPlaybackPosition');
    return Duration(milliseconds: posMs ?? 0);
  }

  @override
  Future<Duration> getPlaybackDuration() async {
    final durMs = await methodChannel.invokeMethod<int>('getPlaybackDuration');
    return Duration(milliseconds: durMs ?? 0);
  }

  @override
  Future<bool> dispose() async {
    final res = await methodChannel.invokeMethod<bool>('dispose');
    return res ?? false;
  }

  @override
  Stream<TranscriptionResult> get transcriptionStream {
    _transcriptionStream ??= _transcriptionEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .map((event) => TranscriptionResult.fromMap(event as Map<dynamic, dynamic>));
    return _transcriptionStream!;
  }

  @override
  Stream<RecordingState> get recordingStateStream {
    _recordingStateStream ??= _recordingStateEventChannel
        .receiveBroadcastStream()
        .map((event) => RecordingState.fromWireString(event?.toString()));
    return _recordingStateStream!;
  }

  @override
  Stream<PlaybackState> get playbackStateStream {
    _playbackStateStream ??= _playbackStateEventChannel
        .receiveBroadcastStream()
        .map((event) => PlaybackState.fromWireString(event?.toString()));
    return _playbackStateStream!;
  }

  @override
  Stream<double> get amplitudeStream {
    _amplitudeStream ??= _amplitudeEventChannel
        .receiveBroadcastStream()
        .map((event) => (event as num?)?.toDouble() ?? 0.0);
    return _amplitudeStream!;
  }

  @override
  Stream<Duration> get durationStream {
    _durationStream ??= _durationEventChannel
        .receiveBroadcastStream()
        .map((event) => Duration(milliseconds: (event as num?)?.toInt() ?? 0));
    return _durationStream!;
  }

  @override
  Stream<SpeechRecorderException> get errorStream {
    _errorStream ??= _errorEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map)
        .map((event) => SpeechRecorderException.fromMap(event as Map<dynamic, dynamic>));
    return _errorStream!;
  }
}
