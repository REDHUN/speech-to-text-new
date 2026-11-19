# Changelog

## Unreleased

- A connected Bluetooth headset's microphone is now used for recording and transcription. Previously the phone's built-in microphone was always used. Headsets that connect or disconnect mid-recording are followed, and `BLUETOOTH_MIC_UNAVAILABLE` is reported when the headset microphone cannot be activated.
- The plugin adds the `MODIFY_AUDIO_SETTINGS` permission, which Android grants automatically.

## 2.0.0

### Breaking changes

- Live transcription now requires Android 13 (API 33). Android 6–12 (API 23–32) record audio and report `TRANSCRIPTION_UNAVAILABLE`. The non-functional fallback backend is removed.
- Methods throw `SpeechRecorderException` instead of `PlatformException`.
- `initialize()` throws `ALREADY_RECORDING` during a recording, and `play()` throws `RECORDING_ACTIVE`.
- `dispose()` and engine teardown now finalize an active recording instead of deleting it.
- `stop()` throws `NO_RECORDING` when nothing was recorded.
- `RecognitionCapability.recommendedMode` is a `RecognitionMode`, and `TranscriptionSegment.confidence` is nullable.
- The unused Dart `SentenceFormatter` and `TranscriptManager` are removed.
- The plugin no longer adds the `INTERNET` permission.

### Recording reliability

- `start()` fails when the microphone cannot be opened, and a rejected `start()` no longer affects the active recording.
- Added a microphone foreground service for background recording, and warnings when Android silences the microphone or changes the input device.
- A microphone stream that fails silently and delivers only digital silence is detected and reopened automatically.
- WAV headers are refreshed during recording, and interrupted files are repaired by `listRecordings()`.
- Disk writes moved to a dedicated thread, and write failures end the recording with a fatal error while keeping its audio.
- Added the `RecordingState.error` state and `RecordingResult.failureCode`.
- Pausing closes the microphone. Commands issued during a transition run in order.

### Transcription

- `automatic` now prefers an installed on-device model.
- One recognition session stays open for the whole recording where the recognizer supports it.
- Restarts replay recent audio and back off after failures. Partial text survives recognizer errors, and `stop()` waits briefly for final results.
- A recognizer that ignores the shared audio stream is detected and reported.
- Formatting keeps recognizer punctuation and no longer splits ordinary clauses. Duplicate removal only applies after replayed audio.
- Transcript updates are incremental, and segments report real recognizer confidence.

### Playback and API

- Added audio focus handling, pausing when headphones disconnect, and a retained playback speed.
- Added `playbackProgressStream`, `getRecordingState()`, `getPlaybackState()`, `listRecordings()` and `deleteRecording()`.

## 1.0.0

- Initial release of `live_speech_recorder`.
- Single-microphone architecture using continuous Android `AudioRecord` capture.
- Real-time PCM audio distribution across WAV file writing and speech recognition pipelines.
- Live partial and confirmed transcription streaming via Dart streams.
- Long-form transcription support with automatic session recovery across speech recognition timeouts.
- Intelligent sentence formatting engine with automatic boundary detection, capitalization, and punctuation.
- Offline and online recognition modes with device capability detection.
- Audio playback subsystem using Android MediaPlayer with seek and variable playback speed.
- Full MVVM/Provider example application for educational speech capture.
