import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'live_speech_recorder_method_channel.dart';
import 'src/models/playback_state.dart';
import 'src/models/recognition_capability.dart';
import 'src/models/recognition_mode.dart';
import 'src/models/recording_result.dart';
import 'src/models/recording_state.dart';
import 'src/models/speech_recorder_exception.dart';
import 'src/models/transcription_result.dart';

abstract class LiveSpeechRecorderPlatform extends PlatformInterface {
  LiveSpeechRecorderPlatform() : super(token: _token);

  static final Object _token = Object();

  static LiveSpeechRecorderPlatform _instance = MethodChannelLiveSpeechRecorder();

  static LiveSpeechRecorderPlatform get instance => _instance;

  static set instance(LiveSpeechRecorderPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('getPlatformVersion() has not been implemented.');
  }

  Future<RecognitionCapability> checkCapabilities() {
    throw UnimplementedError('checkCapabilities() has not been implemented.');
  }

  Future<bool> initialize({
    String language = 'en-IN',
    int sampleRate = 16000,
    RecognitionMode recognitionMode = RecognitionMode.automatic,
    bool enablePunctuation = true,
    bool enableSegmented = true,
  }) {
    throw UnimplementedError('initialize() has not been implemented.');
  }

  Future<String> start({String? outputPath}) {
    throw UnimplementedError('start() has not been implemented.');
  }

  Future<bool> pause() {
    throw UnimplementedError('pause() has not been implemented.');
  }

  Future<bool> resume() {
    throw UnimplementedError('resume() has not been implemented.');
  }

  Future<RecordingResult> stop() {
    throw UnimplementedError('stop() has not been implemented.');
  }

  Future<bool> cancel() {
    throw UnimplementedError('cancel() has not been implemented.');
  }

  Future<bool> play({String? filePath}) {
    throw UnimplementedError('play() has not been implemented.');
  }

  Future<bool> pausePlayback() {
    throw UnimplementedError('pausePlayback() has not been implemented.');
  }

  Future<bool> resumePlayback() {
    throw UnimplementedError('resumePlayback() has not been implemented.');
  }

  Future<bool> stopPlayback() {
    throw UnimplementedError('stopPlayback() has not been implemented.');
  }

  Future<bool> seekTo(Duration position) {
    throw UnimplementedError('seekTo() has not been implemented.');
  }

  Future<bool> setPlaybackSpeed(double speed) {
    throw UnimplementedError('setPlaybackSpeed() has not been implemented.');
  }

  Future<Duration> getPlaybackPosition() {
    throw UnimplementedError('getPlaybackPosition() has not been implemented.');
  }

  Future<Duration> getPlaybackDuration() {
    throw UnimplementedError('getPlaybackDuration() has not been implemented.');
  }

  Future<bool> dispose() {
    throw UnimplementedError('dispose() has not been implemented.');
  }

  Stream<TranscriptionResult> get transcriptionStream {
    throw UnimplementedError('transcriptionStream has not been implemented.');
  }

  Stream<RecordingState> get recordingStateStream {
    throw UnimplementedError('recordingStateStream has not been implemented.');
  }

  Stream<PlaybackState> get playbackStateStream {
    throw UnimplementedError('playbackStateStream has not been implemented.');
  }

  Stream<double> get amplitudeStream {
    throw UnimplementedError('amplitudeStream has not been implemented.');
  }

  Stream<Duration> get durationStream {
    throw UnimplementedError('durationStream has not been implemented.');
  }

  Stream<SpeechRecorderException> get errorStream {
    throw UnimplementedError('errorStream has not been implemented.');
  }
}
