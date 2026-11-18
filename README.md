# LiveSpeechRecorder (`live_speech_recorder`)

A production-ready Flutter plugin for Android that provides **simultaneous single-microphone lossless WAV audio recording** and **real-time live speech-to-text transcription**.

Designed specifically for educational exams, oral assessments, interview platforms, voice notes, and long-form spoken answers where users speak continuously while the application captures clean audio and displays live transcription.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Key Features](#key-features)
- [Installation](#installation)
- [Android Setup](#android-setup)
- [Quick Start Guide](#quick-start-guide)
- [Complete Integration Example](#complete-integration-example)
- [API Reference](#api-reference)
  - [Methods](#methods)
  - [Streams](#streams)
  - [Data Models](#data-models)
- [Troubleshooting & Best Practices](#troubleshooting--best-practices)

---

## Architecture Overview

Traditional speech-to-text solutions on Android attempt to open an independent microphone session, which fails or clashes when another component (such as an audio recorder) is already capturing from the microphone.

`LiveSpeechRecorder` solves this by capturing from the microphone **once** through a single native Android `AudioRecord` instance and distributing the raw PCM stream:

```
[Android Microphone]
        │
   (AudioRecord) ─── Single 16 kHz, 16-bit Mono PCM Capture
        │
 [PcmAudioDistributor]
   ├── Direct Disk Pipe ────────► [WavFileWriter] ──────► [Persistent .WAV File]
   │
   └── Decoupled Ring Buffer ───► [Speech Recognizer] ──► [Live Transcription Streams]
```

- **Zero Blocking**: Disk writes take priority on dedicated threads. Slow speech recognition will never cause audio frame drops.
- **Audio Source Injection**: On Android 13+ (API 33+), PCM is directly piped into Android's `SpeechRecognizer` using `ParcelFileDescriptor` via `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
- **Fallback Stream Engine**: On devices where audio injection or Google speech services are unavailable, the plugin falls back to its internal PCM stream processor without opening a second microphone.

---

## Key Features

- **Simultaneous Capture**: Record standard lossless 16 kHz Mono WAV audio while transcribing speech in real-time from the same microphone.
- **Continuous Long-Form Speech**: Automatically handles Android SpeechRecognizer silence timeouts and session restarts without interrupting WAV recording.
- **Boundary Deduplication**: Uses suffix-prefix overlap algorithms so repeated words across session restarts are eliminated.
- **Intelligent Formatting**:
  - Sentence boundary punctuation (`.`, `?`)
  - Auto-capitalization of sentences and standalone pronouns
  - Acronym preservation (e.g., API, SDK, AI)
  - Paragraph formatting based on natural speech pauses (VAD)
- **Offline / On-Device Recognition**: Supports `offlineOnly`, `onlineAllowed`, and `automatic` recognition modes.
- **Integrated Audio Player**: Native player with real-time progress polling, seek control, and variable playback speeds (0.5x to 2.0x).
- **Persistent Storage**: Saves audio files securely in `context.filesDir/recordings/` to prevent OS cache clearers from removing user recordings.

---

## Installation

Add `live_speech_recorder` and `permission_handler` to your application's `pubspec.yaml`:

### Option A: Local Path Dependency (Within Same Workspace)
```yaml
dependencies:
  flutter:
    sdk: flutter
  permission_handler: ^11.3.1
  live_speech_recorder:
    path: ../speech-to-text-new # Adjust relative path to the package
```

### Option B: Git Repository Dependency
```yaml
dependencies:
  flutter:
    sdk: flutter
  permission_handler: ^11.3.1
  live_speech_recorder:
    git:
      url: https://github.com/your-username/speech-to-text-new.git
      ref: main
```

Run in terminal:
```bash
flutter pub get
```

---

## Android Setup

### 1. Permissions (`AndroidManifest.xml`)

Open `android/app/src/main/AndroidManifest.xml` and add the following inside the `<manifest>` tag:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Microphone and Network Permissions -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Query Android Speech Recognition Services (Required on Android 11+ / API 30+) -->
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>

    <application ...>
        ...
    </application>
</manifest>
```

### 2. Android SDK Requirements

Open `android/app/build.gradle` (or `android/app/build.gradle.kts`) and verify the minimum SDK version:

```groovy
android {
    defaultConfig {
        minSdkVersion 24 // Android 7.0 or higher
        compileSdkVersion 34 // Android 14+ recommended
    }
}
```

---

## Quick Start Guide

### Step 1: Request Microphone Permission

Always request microphone permission at runtime before initializing or starting the recorder:

```dart
import 'package:permission_handler/permission_handler.dart';

final status = await Permission.microphone.request();
if (!status.isGranted) {
  // Handle permission denied
  return;
}
```

### Step 2: Initialize the Recorder

```dart
import 'package:live_speech_recorder/live_speech_recorder.dart';

final recorder = LiveSpeechRecorder();

// Check device capabilities
final capabilities = await recorder.checkCapabilities();
print('Supports Offline: ${capabilities.supportsOfflineRecognition}');

// Initialize recorder engine
await recorder.initialize(
  language: 'en-US', // e.g., 'en-US', 'en-IN', 'en-GB'
  sampleRate: 16000,
  recognitionMode: RecognitionMode.automatic,
  enablePunctuation: true,
  enableSegmented: true,
);
```

### Step 3: Listen to Real-Time Streams

```dart
// Listen to real-time transcription
recorder.transcriptionStream.listen((result) {
  print('Full formatted text: ${result.fullText}');
  print('Current partial hypothesis: ${result.partialText}');
});

// Listen to audio amplitude (normalized 0.0 - 1.0) for visualizers
recorder.amplitudeStream.listen((amplitude) {
  print('Current volume: $amplitude');
});

// Listen to recording duration
recorder.durationStream.listen((duration) {
  print('Recorded: ${duration.inSeconds} seconds');
});

// Listen to errors
recorder.errorStream.listen((error) {
  print('Error [${error.code}]: ${error.message}');
});
```

### Step 4: Record, Stop, and Play

```dart
// Start recording audio and transcribing
final audioFilePath = await recorder.start();

// Pause / Resume if needed
await recorder.pause();
await recorder.resume();

// Stop and retrieve the finalized result
final RecordingResult result = await recorder.stop();
print('WAV File Path: ${result.audioPath}');
print('Final Duration: ${result.duration}');
print('Final Transcript: ${result.transcription}');

// Play back the recorded audio
await recorder.play(filePath: result.audioPath);

// Adjust playback speed
await recorder.setPlaybackSpeed(1.25);

// Stop playback
await recorder.stopPlayback();

// Release resources when finished
await recorder.dispose();
```

---

## Complete Integration Example

Below is a complete, copy-pasteable Flutter `StatefulWidget` demonstrating live speech transcription, real-time waveform visualization, stop-and-save, and audio playback:

```dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';

class SpeechRecorderScreen extends StatefulWidget {
  const SpeechRecorderScreen({super.key});

  @override
  State<SpeechRecorderScreen> createState() => _SpeechRecorderScreenState();
}

class _SpeechRecorderScreenState extends State<SpeechRecorderScreen> {
  final LiveSpeechRecorder _recorder = LiveSpeechRecorder();

  // State
  bool _isRecording = false;
  bool _isPaused = false;
  bool _isPlaying = false;

  String _transcription = '';
  String _partialText = '';
  Duration _duration = Duration.zero;
  double _amplitude = 0.0;
  RecordingResult? _lastResult;

  // Stream Subscriptions
  StreamSubscription<TranscriptionResult>? _transcriptionSub;
  StreamSubscription<RecordingState>? _recordingStateSub;
  StreamSubscription<PlaybackState>? _playbackStateSub;
  StreamSubscription<double>? _amplitudeSub;
  StreamSubscription<Duration>? _durationSub;
  StreamSubscription<SpeechRecorderException>? _errorSub;

  @override
  void initState() {
    super.initState();
    _initSubscribers();
  }

  void _initSubscribers() {
    _transcriptionSub = _recorder.transcriptionStream.listen((result) {
      setState(() {
        _transcription = result.confirmedText;
        _partialText = result.partialText;
      });
    });

    _recordingStateSub = _recorder.recordingStateStream.listen((state) {
      setState(() {
        _isRecording = state == RecordingState.recording;
        _isPaused = state == RecordingState.paused;
      });
    });

    _playbackStateSub = _recorder.playbackStateStream.listen((state) {
      setState(() {
        _isPlaying = state == PlaybackState.playing;
      });
    });

    _amplitudeSub = _recorder.amplitudeStream.listen((amp) {
      setState(() => _amplitude = amp);
    });

    _durationSub = _recorder.durationStream.listen((dur) {
      setState(() => _duration = dur);
    });

    _errorSub = _recorder.errorStream.listen((err) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error: ${err.message}')),
      );
    });
  }

  Future<void> _startRecording() async {
    // 1. Request microphone permission
    final status = await Permission.microphone.request();
    if (!status.isGranted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Microphone permission denied')),
      );
      return;
    }

    // 2. Clear old state
    setState(() {
      _transcription = '';
      _partialText = '';
      _lastResult = null;
      _duration = Duration.zero;
    });

    // 3. Initialize & Start
    await _recorder.initialize(
      language: 'en-US',
      sampleRate: 16000,
      recognitionMode: RecognitionMode.automatic,
      enablePunctuation: true,
    );

    await _recorder.start();
  }

  Future<void> _stopRecording() async {
    final result = await _recorder.stop();
    setState(() {
      _lastResult = result;
      _transcription = result.transcription;
      _partialText = '';
    });
  }

  Future<void> _togglePlayback() async {
    if (_lastResult == null) return;

    if (_isPlaying) {
      await _recorder.pausePlayback();
    } else {
      await _recorder.play(filePath: _lastResult!.audioPath);
    }
  }

  @override
  void dispose() {
    _transcriptionSub?.cancel();
    _recordingStateSub?.cancel();
    _playbackStateSub?.cancel();
    _amplitudeSub?.cancel();
    _durationSub?.cancel();
    _errorSub?.cancel();
    _recorder.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Speech Recorder & Transcriber')),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            // Timer & Amplitude Indicator
            Text(
              '${_duration.inMinutes.toString().padLeft(2, '0')}:${(_duration.inSeconds % 60).toString().padLeft(2, '0')}',
              style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            LinearProgressIndicator(value: _amplitude.clamp(0.0, 1.0)),
            const SizedBox(height: 24),

            // Transcription Display Box
            Expanded(
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Colors.grey.shade100,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.grey.shade300),
                ),
                child: SingleChildScrollView(
                  child: RichText(
                    text: TextSpan(
                      style: const TextStyle(fontSize: 16, color: Colors.black87, height: 1.4),
                      children: [
                        TextSpan(text: _transcription),
                        if (_transcription.isNotEmpty && _partialText.isNotEmpty)
                          const TextSpan(text: ' '),
                        TextSpan(
                          text: _partialText,
                          style: const TextStyle(color: Colors.blue, fontStyle: FontStyle.italic),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Controls
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                if (!_isRecording)
                  ElevatedButton.icon(
                    onPressed: _startRecording,
                    icon: const Icon(Icons.mic),
                    label: const Text('Start Recording'),
                  )
                else
                  ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                    onPressed: _stopRecording,
                    icon: const Icon(Icons.stop),
                    label: const Text('Stop & Save'),
                  ),

                if (!_isRecording && _lastResult != null)
                  ElevatedButton.icon(
                    onPressed: _togglePlayback,
                    icon: Icon(_isPlaying ? Icons.pause : Icons.play_arrow),
                    label: Text(_isPlaying ? 'Pause Audio' : 'Play Audio'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
```

---

## API Reference

### Methods

| Method | Return Type | Description |
| :--- | :--- | :--- |
| `checkCapabilities()` | `Future<RecognitionCapability>` | Inspects device support for SpeechRecognizer, audio source injection, and offline speech recognition. |
| `initialize({...})` | `Future<bool>` | Configures language (`language`), sample rate (`sampleRate`), recognition mode (`recognitionMode`), punctuation, and segmentation. |
| `start({String? outputPath})` | `Future<String>` | Starts single-mic capture, begins writing WAV file, and starts live transcription. Returns the WAV output path. |
| `pause()` | `Future<bool>` | Temporarily pauses audio capture and speech recognition. |
| `resume()` | `Future<bool>` | Resumes capture and transcription from where it was paused. |
| `stop()` | `Future<RecordingResult>` | Finalizes the WAV header, stops recognition, and returns `RecordingResult`. |
| `cancel()` | `Future<bool>` | Cancels the active recording session and discards the in-progress audio file. |
| `play({String? filePath})` | `Future<bool>` | Starts playback of the recorded WAV audio file. |
| `pausePlayback()` | `Future<bool>` | Pauses audio playback. |
| `resumePlayback()` | `Future<bool>` | Resumes paused audio playback. |
| `stopPlayback()` | `Future<bool>` | Stops audio playback and resets position to 0. |
| `seekTo(Duration position)` | `Future<bool>` | Seeks playback to the specified duration. |
| `setPlaybackSpeed(double speed)` | `Future<bool>` | Adjusts playback speed (e.g. `0.8`, `1.0`, `1.25`, `1.5`, `2.0`). |
| `getPlaybackPosition()` | `Future<Duration>` | Returns the current playback position. |
| `getPlaybackDuration()` | `Future<Duration>` | Returns the total duration of the currently loaded audio file. |
| `dispose()` | `Future<bool>` | Cleans up native audio capture, speech recognition, and player resources. |

---

### Streams

| Stream | Type | Description |
| :--- | :--- | :--- |
| `transcriptionStream` | `Stream<TranscriptionResult>` | Real-time speech transcription (contains `fullText`, `partialText`, `confirmedText`, `segments`). |
| `recordingStateStream` | `Stream<RecordingState>` | Lifecycle states: `uninitialized`, `recording`, `paused`, `stopped`. |
| `playbackStateStream` | `Stream<PlaybackState>` | Player states: `idle`, `playing`, `paused`, `completed`, `error`. |
| `amplitudeStream` | `Stream<double>` | Real-time RMS volume normalized from `0.0` to `1.0` for visualizers. |
| `durationStream` | `Stream<Duration>` | Active elapsed recording duration. |
| `errorStream` | `Stream<SpeechRecorderException>` | Stream of fatal and non-fatal errors with `code` and `message`. |

---

### Data Models

#### `TranscriptionResult`
- `String fullText`: Formatted combined transcript (confirmed history + current partial hypothesis).
- `String partialText`: Current active hypothesis being spoken in real-time.
- `String confirmedText`: Immutable finalized history of confirmed sentences.
- `bool isFinal`: True when the segment has reached end of utterance.
- `List<TranscriptionSegment> segments`: List of individual confirmed segments with start/end millisecond timestamps.

#### `RecordingResult`
- `String audioPath`: Absolute path to the saved `.wav` audio file on disk.
- `String transcription`: Complete final formatted transcript.
- `Duration duration`: Total audio duration.
- `int fileSizeBytes`: File size in bytes.
- `int sampleRate`: PCM sample rate (default 16000 Hz).
- `int channels`: Audio channel count (1 for mono).

#### `RecognitionMode`
- `RecognitionMode.automatic`: Prefers on-device offline recognition when available, falls back to online recognition.
- `RecognitionMode.offlineOnly`: Strictly requires on-device speech models (fails gracefully if offline language pack is missing).
- `RecognitionMode.onlineAllowed`: Uses cloud/network speech recognition.

---

## Troubleshooting & Best Practices

### 1. Always Stop Recording Before Playing Audio
Attempting to play audio while recording is still active can cause acoustic feedback loops. Always call `await recorder.stop()` before calling `await recorder.play()`.

### 2. Guard Stale UI State
When starting a new recording, clear your UI's previous `RecordingResult` or hide the playback controls until `stop()` is called.

### 3. Android Emulators & Microphone Input
When testing on an Android Virtual Device (AVD):
- Open **AVD Settings** -> **Virtual Microphone** and ensure **Host audio input** is enabled.
- Google Play speech recognition requires an internet connection on emulators unless an on-device offline language pack is downloaded.

### 4. Handling Missing Permissions
If you do not request microphone permission before calling `start()`, `SpeechRecorderException` with code `PERMISSION_DENIED` will be emitted via `errorStream`. Always request permission with `permission_handler` first.
