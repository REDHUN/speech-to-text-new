import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';
import 'package:live_speech_recorder/live_speech_recorder_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platform = MethodChannelLiveSpeechRecorder();
  const channel = MethodChannel('com.livespeechrecorder/methods');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      switch (methodCall.method) {
        case 'getPlatformVersion':
          return 'Android 14 (API 34)';
        case 'checkCapabilities':
          return {
            'hasSpeechRecognizer': true,
            'supportsAudioSourceInjection': true,
            'supportsOfflineRecognition': true,
            'availableServices': ['com.google.android.tts'],
            'defaultServicePackage': 'com.google.android.tts',
            'recommendedMode': 'offlineOnly',
          };
        case 'initialize':
          return true;
        case 'start':
          return '/data/user/0/com.app/cache/recordings/test.wav';
        case 'pause':
          return true;
        case 'resume':
          return true;
        case 'stop':
          return {
            'audioPath': '/data/user/0/com.app/cache/recordings/test.wav',
            'transcription': 'Test transcription output.',
            'durationMs': 5000,
            'fileSizeBytes': 160044,
            'sampleRate': 16000,
            'channels': 1,
          };
        case 'cancel':
          return true;
        case 'play':
          return true;
        case 'pausePlayback':
          return true;
        case 'resumePlayback':
          return true;
        case 'stopPlayback':
          return true;
        case 'seekTo':
          return true;
        case 'setPlaybackSpeed':
          return true;
        case 'getPlaybackPosition':
          return 1500;
        case 'getPlaybackDuration':
          return 5000;
        case 'dispose':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion returns mock version', () async {
    expect(await platform.getPlatformVersion(), 'Android 14 (API 34)');
  });

  test('checkCapabilities returns typed capability model', () async {
    final cap = await platform.checkCapabilities();
    expect(cap.hasSpeechRecognizer, isTrue);
    expect(cap.supportsAudioSourceInjection, isTrue);
    expect(cap.supportsOfflineRecognition, isTrue);
    expect(cap.recommendedMode, 'offlineOnly');
  });

  test('initialize executes successfully', () async {
    final initialized = await platform.initialize(
      language: 'en-IN',
      sampleRate: 16000,
      recognitionMode: RecognitionMode.offlineOnly,
    );
    expect(initialized, isTrue);
  });

  test('start returns audio file path', () async {
    final path = await platform.start();
    expect(path, '/data/user/0/com.app/cache/recordings/test.wav');
  });

  test('pause and resume succeed', () async {
    expect(await platform.pause(), isTrue);
    expect(await platform.resume(), isTrue);
  });

  test('stop returns typed RecordingResult', () async {
    final result = await platform.stop();
    expect(result.audioPath, '/data/user/0/com.app/cache/recordings/test.wav');
    expect(result.transcription, 'Test transcription output.');
    expect(result.duration, const Duration(seconds: 5));
    expect(result.fileSizeBytes, 160044);
    expect(result.sampleRate, 16000);
    expect(result.channels, 1);
  });

  test('playback controls function properly', () async {
    expect(await platform.play(filePath: 'test.wav'), isTrue);
    expect(await platform.pausePlayback(), isTrue);
    expect(await platform.resumePlayback(), isTrue);
    expect(await platform.seekTo(const Duration(seconds: 2)), isTrue);
    expect(await platform.setPlaybackSpeed(1.5), isTrue);
    expect(await platform.getPlaybackPosition(), const Duration(milliseconds: 1500));
    expect(await platform.getPlaybackDuration(), const Duration(milliseconds: 5000));
    expect(await platform.stopPlayback(), isTrue);
  });
}
