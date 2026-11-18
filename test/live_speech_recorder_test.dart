import 'package:flutter_test/flutter_test.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';
import 'package:live_speech_recorder/live_speech_recorder_method_channel.dart';
import 'package:live_speech_recorder/live_speech_recorder_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockLiveSpeechRecorderPlatform
    with MockPlatformInterfaceMixin
    implements LiveSpeechRecorderPlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('Android 14 (API 34)');

  @override
  Future<RecognitionCapability> checkCapabilities() => Future.value(
        const RecognitionCapability(
          hasSpeechRecognizer: true,
          supportsAudioSourceInjection: true,
          supportsOfflineRecognition: true,
          availableServices: ['com.google.android.tts'],
          recommendedMode: 'offlineOnly',
        ),
      );

  @override
  Future<bool> initialize({
    String language = 'en-IN',
    int sampleRate = 16000,
    RecognitionMode recognitionMode = RecognitionMode.automatic,
    bool enablePunctuation = true,
    bool enableSegmented = true,
  }) =>
      Future.value(true);

  @override
  Future<String> start({String? outputPath}) =>
      Future.value('/data/test/recording.wav');

  @override
  Future<bool> pause() => Future.value(true);

  @override
  Future<bool> resume() => Future.value(true);

  @override
  Future<RecordingResult> stop() => Future.value(
        const RecordingResult(
          audioPath: '/data/test/recording.wav',
          transcription: 'Final confirmed student explanation.',
          duration: Duration(seconds: 12),
          fileSizeBytes: 384044,
          sampleRate: 16000,
          channels: 1,
        ),
      );

  @override
  Future<bool> cancel() => Future.value(true);

  @override
  Future<bool> play({String? filePath}) => Future.value(true);

  @override
  Future<bool> pausePlayback() => Future.value(true);

  @override
  Future<bool> resumePlayback() => Future.value(true);

  @override
  Future<bool> stopPlayback() => Future.value(true);

  @override
  Future<bool> seekTo(Duration position) => Future.value(true);

  @override
  Future<bool> setPlaybackSpeed(double speed) => Future.value(true);

  @override
  Future<Duration> getPlaybackPosition() =>
      Future.value(const Duration(seconds: 3));

  @override
  Future<Duration> getPlaybackDuration() =>
      Future.value(const Duration(seconds: 12));

  @override
  Future<bool> dispose() => Future.value(true);

  @override
  Stream<TranscriptionResult> get transcriptionStream =>
      Stream.value(const TranscriptionResult(
        fullText: 'Live speech transcription test.',
        partialText: 'test',
        confirmedText: 'Live speech transcription',
        isFinal: true,
      ));

  @override
  Stream<RecordingState> get recordingStateStream =>
      Stream.value(RecordingState.recording);

  @override
  Stream<PlaybackState> get playbackStateStream =>
      Stream.value(PlaybackState.idle);

  @override
  Stream<double> get amplitudeStream => Stream.value(0.65);

  @override
  Stream<Duration> get durationStream =>
      Stream.value(const Duration(seconds: 5));

  @override
  Stream<SpeechRecorderException> get errorStream => const Stream.empty();
}

void main() {
  final LiveSpeechRecorderPlatform initialPlatform =
      LiveSpeechRecorderPlatform.instance;

  test('$MethodChannelLiveSpeechRecorder is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelLiveSpeechRecorder>());
  });

  group('LiveSpeechRecorder Public API Tests', () {
    late LiveSpeechRecorder recorder;
    late MockLiveSpeechRecorderPlatform fakePlatform;

    setUp(() {
      recorder = LiveSpeechRecorder();
      fakePlatform = MockLiveSpeechRecorderPlatform();
      LiveSpeechRecorderPlatform.instance = fakePlatform;
    });

    test('getPlatformVersion returns mock platform', () async {
      expect(await recorder.getPlatformVersion(), 'Android 14 (API 34)');
    });

    test('checkCapabilities returns typed capability', () async {
      final cap = await recorder.checkCapabilities();
      expect(cap.supportsAudioSourceInjection, isTrue);
      expect(cap.supportsOfflineRecognition, isTrue);
    });

    test('initialize, start, pause, resume, stop flow', () async {
      final init = await recorder.initialize(
        language: 'en-IN',
        sampleRate: 16000,
        recognitionMode: RecognitionMode.offlineOnly,
      );
      expect(init, isTrue);

      final audioPath = await recorder.start();
      expect(audioPath, '/data/test/recording.wav');

      expect(await recorder.pause(), isTrue);
      expect(await recorder.resume(), isTrue);

      final result = await recorder.stop();
      expect(result.audioPath, '/data/test/recording.wav');
      expect(result.duration, const Duration(seconds: 12));
      expect(result.transcription, 'Final confirmed student explanation.');
    });

    test('playback controls flow', () async {
      expect(await recorder.play(), isTrue);
      expect(await recorder.pausePlayback(), isTrue);
      expect(await recorder.resumePlayback(), isTrue);
      expect(await recorder.seekTo(const Duration(seconds: 5)), isTrue);
      expect(await recorder.setPlaybackSpeed(1.25), isTrue);
      expect(await recorder.getPlaybackPosition(), const Duration(seconds: 3));
      expect(await recorder.getPlaybackDuration(), const Duration(seconds: 12));
      expect(await recorder.stopPlayback(), isTrue);
    });

    test('streams emit expected typed values', () async {
      final trans = await recorder.transcriptionStream.first;
      expect(trans.fullText, 'Live speech transcription test.');
      expect(trans.isFinal, isTrue);

      final state = await recorder.recordingStateStream.first;
      expect(state, RecordingState.recording);

      final amp = await recorder.amplitudeStream.first;
      expect(amp, 0.65);

      final dur = await recorder.durationStream.first;
      expect(dur, const Duration(seconds: 5));
    });
  });
}
