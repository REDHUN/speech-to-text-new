# Changelog

## 1.0.0

- Initial release of `live_speech_recorder`.
- Single-microphone architecture using continuous Android `AudioRecord` capture.
- Real-time PCM audio distribution across WAV file writing and speech recognition pipelines.
- Live partial and confirmed transcription streaming via Dart streams.
- Long-form transcription support with automatic session recovery across speech recognition timeouts.
- Intelligent sentence formatting engine with automatic boundary detection, capitalization, and punctuation.
- Offline and online recognition modes with device capability detection.
- Audio playback subsystem using Android MediaPlayer with seek and variable playback speed.
- Comprehensive unit and integration test coverage.
- Full MVVM/Provider example application for educational speech capture.
