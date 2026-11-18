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
    expect(cap.recommendedMode, RecognitionMode.offlineOnly);
  });

  test('native failures become SpeechRecorderException', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      throw PlatformException(code: 'MIC_BUSY', message: 'busy');
    });

    await expectLater(
      platform.start(),
      throwsA(
        isA<SpeechRecorderException>()
            .having((e) => e.code, 'code', 'MIC_BUSY')
            .having((e) => e.message, 'message', 'busy'),
      ),
    );
  });

  test('error events carry the fatal flag', () {
    final error = SpeechRecorderException.fromMap(const {
      'code': 'DISK_WRITE_FAILED',
      'message': 'full',
      'isFatal': true,
    });
    expect(error.isFatal, isTrue);
  });

  test('recording results expose failure details', () {
    final result = RecordingResult.fromMap(const {
      'audioPath': 'a.wav',
      'durationMs': 0,
      'failureCode': 'CAPTURE_FAILED',
    });
    expect(result.failureCode, 'CAPTURE_FAILED');
    expect(result.hasAudio, isFalse);
  });

  test('recording state understands the error state', () {
    expect(RecordingState.fromWireString('ERROR'), RecordingState.error);
  });

  test('transcript is assembled from incremental updates', () async {
    const eventChannel = EventChannel('com.livespeechrecorder/transcription');
    Map<String, Object?> segment(String text, {bool paragraph = false}) => {
          'text': text,
          'startTimeMs': 0,
          'endTimeMs': 10,
          'confidence': null,
          'isFinal': true,
          'startsParagraph': paragraph,
        };

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      eventChannel,
      MockStreamHandler.inline(
        onListen: (arguments, events) {
          events.success({
            'isReset': true,
            'segmentStartIndex': 0,
            'segments': [segment('First.')],
            'partialText': 'sec',
            'formattedPartial': 'Sec',
            'isFinal': false,
          });
          events.success({
            'isReset': false,
            'segmentStartIndex': 1,
            'segments': [segment('Second.', paragraph: true)],
            'partialText': '',
            'formattedPartial': '',
            'isFinal': true,
          });
          events.endOfStream();
        },
      ),
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockStreamHandler(eventChannel, null);
    });

    final results = await MethodChannelLiveSpeechRecorder().transcriptionStream.toList();

    expect(results.first.fullText, 'First. Sec');
    expect(results.first.confirmedText, 'First.');
    expect(results.last.fullText, 'First.\n\nSecond.');
    expect(results.last.segments, hasLength(2));
    expect(results.last.segments.first.confidence, isNull);
    expect(results.last.isFinal, isTrue);
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
