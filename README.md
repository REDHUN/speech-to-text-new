# LiveSpeechRecorder

A Flutter Android plugin providing simultaneous audio recording and real-time speech transcription from a single audio capture session.

Designed specifically for educational applications, essays, oral examinations, and long-form spoken answers where students speak continuously while the application records lossless audio and displays live transcription.

## Architecture

The plugin uses a single Android `AudioRecord` session:

```
[Android Microphone]
        │
  (AudioRecord)
        │
 [Raw PCM Stream] ─── (16 kHz, 16-bit Mono)
        │
 [PcmAudioDistributor]
   ├── Synchronous Disk Pipe ──────► [WavFileWriter] ──────► [Local .WAV Audio File]
   │
   └── Decoupled Bounded Channel ──► [Speech Engine] ──────► [Live Transcription Streams]
```

- **Single Microphone Session**: Audio is captured once via native Android `AudioRecord`.
- **Zero Blocking**: Disk writes to WAV take priority. Slow speech recognition will never cause audio frame drops.
- **Audio Source Injection**: On Android 13+ (API 33+), PCM is injected into `SpeechRecognizer` using `ParcelFileDescriptor` pipes via `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
- **Fallback On-Device Engine**: If the host speech service rejects audio injection or lacks offline models, an on-device stream processor consumes the application-owned PCM stream without opening a second microphone.

## Key Features

1. **Simultaneous Single-Mic Capture**: Audio recording and live transcription operate on the same PCM capture session.
2. **Real-Time Streaming**: Emits live partial hypotheses alongside immutable confirmed history.
3. **Long-Form Continuous Recognition**: Automatically restarts recognition sessions on speech pauses and timeouts without interrupting WAV audio capture.
4. **Boundary Deduplication**: Suffix-prefix word overlap detection eliminates repeated words across recognition restarts.
5. **Intelligent Sentence Formatting**:
   - Automatic sentence boundary detection
   - First word capitalization
   - Standalone pronoun and contraction handling
   - Interrogative detection with question marks
   - Proper noun and technical acronym formatting
   - Paragraph separation on extended pauses
6. **Offline Recognition Support**: Detects on-device model availability and supports `offlineOnly`, `onlineAllowed`, and `automatic` modes.
7. **Audio Playback Subsystem**: Integrated player with seek, duration, and speed adjustment (0.5x - 2.0x).

## Android Setup

### Permissions

Add the following to your application's `android/app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />

<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

### SDK Requirements

- Minimum SDK: Android 12 (API 31)
- Recommended SDK: Android 13+ (API 33+) for native `EXTRA_AUDIO_SOURCE` injection.

## Quick Start

```dart
import 'package:live_speech_recorder/live_speech_recorder.dart';

final recorder = LiveSpeechRecorder();

// 1. Inspect device capabilities
final capabilities = await recorder.checkCapabilities();
print('Offline support: ${capabilities.supportsOfflineRecognition}');

// 2. Initialize
await recorder.initialize(
  language: 'en-IN',
  sampleRate: 16000,
  recognitionMode: RecognitionMode.automatic,
  enablePunctuation: true,
);

// 3. Listen to live transcription
recorder.transcriptionStream.listen((result) {
  print('Full Text: ${result.fullText}');
  print('Partial Hypothesis: ${result.partialText}');
});

// 4. Start recording and transcription
final audioPath = await recorder.start();

// 5. Pause and resume
await recorder.pause();
await recorder.resume();

// 6. Stop and retrieve result
final result = await recorder.stop();
print('Audio Path: ${result.audioPath}');
print('Duration: ${result.duration}');
print('Transcription: ${result.transcription}');

// 7. Audio Playback
await recorder.play();
await recorder.setPlaybackSpeed(1.25);
await recorder.pausePlayback();
await recorder.stopPlayback();

// 8. Clean up
await recorder.dispose();
```

## Example Application

A complete Provider and MVVM example application is included in the `example/` directory demonstrating:
- Live transcription and confirmed sentence display
- Dynamic soundwave visualization
- Audio playback scrubber and speed controller
- Capability diagnostics and mode switching
