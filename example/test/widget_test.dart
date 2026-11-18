import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:live_speech_recorder_example/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.livespeechrecorder/methods');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      if (methodCall.method == 'checkCapabilities') {
        return {
          'hasSpeechRecognizer': true,
          'supportsAudioSourceInjection': true,
          'supportsOfflineRecognition': true,
          'availableServices': ['com.google.android.tts'],
          'recommendedMode': 'offlineOnly',
        };
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('Renders LiveSpeechRecorder example screen', (WidgetTester tester) async {
    await tester.pumpWidget(const LiveSpeechRecorderExampleApp());
    await tester.pump();

    // Verify Title & UI Components
    expect(find.text('Live Speech Transcription & Audio Recording'), findsOneWidget);
    expect(find.text('Start Recording & Transcribing'), findsOneWidget);
    expect(find.text('Single-Mic Capture & Android Capabilities'), findsOneWidget);
  });
}
