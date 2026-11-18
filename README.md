# LiveSpeechRecorder (`live_speech_recorder`)

A Flutter plugin for **Android 13 (API 33) and newer** that provides **simultaneous single-microphone lossless WAV audio recording** and **real-time live speech-to-text transcription**.

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
   ├── Bounded Writer Queue ────► [WavFileWriter] ──────► [Persistent .WAV File]
   │
   └── Bounded Audio Queue ─────► [Speech Recognizer] ──► [Live Transcription Streams]
```

- **Isolated Paths**: Capture, disk writes and recognition each run on their own threads, so a slow recognizer cannot delay the recording. If storage fails or cannot keep up, the recording ends with a fatal error and keeps its audio.
- **Audio Source Injection**: PCM is piped into Android's `SpeechRecognizer` using `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
- **Injection Check**: Some recognition services may ignore injected audio. If one stops reading it, the plugin ends that recognition session and reports `AUDIO_INJECTION_UNSUPPORTED`. Audio recording continues.

---

## Key Features

- **Simultaneous Capture**: Record standard lossless 16 kHz Mono WAV audio while transcribing speech in real-time from the same microphone.
- **Continuous Long-Form Speech**: Keeps one recognition session open where supported, and restarts with backoff when needed, without interrupting WAV recording.
- **Background Recording**: A microphone foreground service keeps capture running when the app leaves the screen.
- **Crash-Tolerant Files**: WAV headers are refreshed during recording, and `listRecordings()` repairs interrupted files.
- **Boundary Deduplication**: Removes words repeated when audio is replayed into a restarted session.
- **Conservative English Formatting**:
  - Keeps punctuation supplied by the recognizer
  - Adds sentence endings and capitalization when it is missing
  - Acronym preservation (e.g., API, SDK, AI)
  - Paragraph breaks after long pauses
- **Offline / On-Device Recognition**: Supports `offlineOnly`, `onlineAllowed`, and `automatic` recognition modes.
- **Integrated Audio Player**: Native player with a progress stream, seek control, audio focus handling, and variable playback speeds (0.5x to 2.0x).
- **Managed Storage**: Saves audio in `context.filesDir/recordings/`, with APIs to list and delete recordings.

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

The plugin adds `RECORD_AUDIO`, the microphone foreground service permissions, and the speech service query automatically. To show the background recording notification, add this to `android/app/src/main/AndroidManifest.xml` and request the permission at runtime:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Apps published on Google Play must declare their microphone foreground service use in Play Console. To opt out, pass `enableBackgroundRecording: false` to `initialize()`. Android then silences the microphone while the app is in the background, and the plugin reports `MIC_SILENCED`.

### 2. Android SDK Requirements

Open `android/app/build.gradle` (or `android/app/build.gradle.kts`) and set the minimum SDK version:

```groovy
android {
    compileSdk 36

    defaultConfig {
        minSdk 33 // Android 13 or higher
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
  // isFatal means the recording has ended. Its audio is still saved.
  print('Error [${error.code}] fatal=${error.isFatal}: ${error.message}');
});
```

### Step 4: Record, Stop, and Play

```dart
// Start recording audio and transcribing.
// Throws SpeechRecorderException if the microphone cannot be opened.
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
| `initialize({...})` | `Future<bool>` | Configures language, sample rate, recognition mode, punctuation, segmentation, and background recording. Throws during a recording. |
| `start({String? outputPath})` | `Future<String>` | Opens the microphone, begins the WAV file and transcription, and returns the WAV path. Throws if capture cannot start. |
| `pause()` | `Future<bool>` | Pauses recording and closes the microphone. Returns `false` if not recording. |
| `resume()` | `Future<bool>` | Reopens the microphone and continues. Returns `false` if not paused. |
| `stop()` | `Future<RecordingResult>` | Finalizes the WAV file and transcript. Repeated calls return the same result. |
| `cancel()` | `Future<bool>` | Discards the active recording and its file. Returns `false` if none is active. |
| `getRecordingState()` | `Future<RecordingState>` | Returns the current recording state. |
| `getPlaybackState()` | `Future<PlaybackState>` | Returns the current playback state. |
| `listRecordings()` | `Future<List<SavedRecording>>` | Lists saved recordings, newest first, repairing interrupted files. |
| `deleteRecording(String path)` | `Future<bool>` | Deletes a recording from the plugin's directory. |
| `play({String? filePath})` | `Future<bool>` | Starts playback of a WAV file. Throws during a recording. |
| `pausePlayback()` | `Future<bool>` | Pauses audio playback. |
| `resumePlayback()` | `Future<bool>` | Resumes paused or completed playback. |
| `stopPlayback()` | `Future<bool>` | Stops audio playback and releases the player. |
| `seekTo(Duration position)` | `Future<bool>` | Seeks playback to the specified position once audio has loaded. |
| `setPlaybackSpeed(double speed)` | `Future<bool>` | Sets playback speed from `0.5` to `2.0`. The speed carries over to later playback. |
| `getPlaybackPosition()` | `Future<Duration>` | Returns the current playback position. |
| `getPlaybackDuration()` | `Future<Duration>` | Returns the duration of the loaded audio file. |
| `dispose()` | `Future<bool>` | Finalizes any active recording, keeps its file, and releases native resources. |

All methods report failures by throwing `SpeechRecorderException`.

---

### Streams

| Stream | Type | Description |
| :--- | :--- | :--- |
| `transcriptionStream` | `Stream<TranscriptionResult>` | Real-time speech transcription (contains `fullText`, `partialText`, `confirmedText`, `segments`). |
| `recordingStateStream` | `Stream<RecordingState>` | Lifecycle states: `uninitialized`, `idle`, `recording`, `paused`, `stopped`, `error`. Starts with the current state. |
| `playbackStateStream` | `Stream<PlaybackState>` | Player states: `idle`, `playing`, `paused`, `completed`, `error`. Starts with the current state. |
| `playbackProgressStream` | `Stream<PlaybackProgress>` | Playback position and duration while audio plays. |
| `amplitudeStream` | `Stream<double>` | Real-time RMS volume normalized from `0.0` to `1.0` for visualizers. |
| `durationStream` | `Stream<Duration>` | Length of the audio recorded so far, excluding pauses. |
| `errorStream` | `Stream<SpeechRecorderException>` | Errors and warnings with `code`, `message`, and `isFatal`. |

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
- `String? failureCode`: The error that ended the recording early, if any.
- `bool hasAudio`: Whether any audio was captured.

#### `SpeechRecorderException`
- `String code`: Stable error identifier, such as `MIC_BUSY` or `DISK_WRITE_FAILED`.
- `String message`: Human-readable explanation.
- `bool isFatal`: True when the error ended the recording. Audio captured so far is kept, and `stop()` returns it.

#### `RecognitionMode`
- `RecognitionMode.automatic`: Uses on-device recognition when the language is installed, otherwise online recognition.
- `RecognitionMode.offlineOnly`: Never uses online recognition. If the language pack is missing, audio is still recorded and `OFFLINE_UNAVAILABLE` is reported.
- `RecognitionMode.onlineAllowed`: Uses cloud/network speech recognition.

---

## Troubleshooting & Best Practices

### 1. Always Stop Recording Before Playing Audio
Playing audio during a recording would feed it back into the microphone, so `play()` throws `RECORDING_ACTIVE`. Call `await recorder.stop()` first.

### 2. Guard Stale UI State
When starting a new recording, clear your UI's previous `RecordingResult` or hide the playback controls until `stop()` is called.

### 3. Android Emulators & Microphone Input
When testing on an Android Virtual Device (AVD):
- Open **AVD Settings** -> **Virtual Microphone** and ensure **Host audio input** is enabled.
- Google Play speech recognition requires an internet connection on emulators unless an on-device offline language pack is downloaded.
- The emulator's virtual microphone can fail with `pcm_readi` I/O errors in logcat. The plugin reports `MIC_INPUT_LOST` and reconnects, but speech during each dropout is lost. Use a physical device to judge transcription quality.

### 4. Handling Missing Permissions
If microphone permission has not been granted, `start()` throws `SpeechRecorderException` with code `PERMISSION_DENIED`. Always request permission with `permission_handler` first.

### 5. Start Recording While the App Is Visible
Android only allows the microphone foreground service to start from the foreground. If it cannot start, recording continues and `BACKGROUND_RECORDING_UNAVAILABLE` is reported.
