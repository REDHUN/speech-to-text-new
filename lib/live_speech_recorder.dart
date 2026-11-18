import 'dart:async';

import 'live_speech_recorder_platform_interface.dart';
import 'src/models/playback_state.dart';
import 'src/models/recognition_capability.dart';
import 'src/models/recognition_mode.dart';
import 'src/models/recording_result.dart';
import 'src/models/recording_state.dart';
import 'src/models/speech_recorder_exception.dart';
import 'src/models/transcription_result.dart';

export 'src/models/playback_state.dart';
export 'src/models/recognition_capability.dart';
export 'src/models/recognition_mode.dart';
export 'src/models/recording_result.dart';
export 'src/models/recording_state.dart';
export 'src/models/speech_recorder_exception.dart';
export 'src/models/transcription_result.dart';
export 'src/models/transcription_segment.dart';
export 'src/sentence_formatter.dart';
export 'src/transcript_manager.dart';

/// A production-ready Flutter plugin providing simultaneous single-microphone audio recording
/// and real-time speech transcription on Android.
class LiveSpeechRecorder {
  LiveSpeechRecorderPlatform get _platform => LiveSpeechRecorderPlatform.instance;

  /// Stream of real-time transcription updates.
  Stream<TranscriptionResult> get transcriptionStream => _platform.transcriptionStream;

  /// Stream of recording lifecycle states (recording, paused, stopped, etc.).
  Stream<RecordingState> get recordingStateStream => _platform.recordingStateStream;

  /// Stream of audio playback lifecycle states (playing, paused, completed, etc.).
  Stream<PlaybackState> get playbackStateStream => _platform.playbackStateStream;

  /// Stream of real-time microphone RMS amplitude normalized between 0.0 and 1.0.
  Stream<double> get amplitudeStream => _platform.amplitudeStream;

  /// Stream of active audio recording elapsed duration.
  Stream<Duration> get durationStream => _platform.durationStream;

  /// Stream of non-fatal and fatal errors.
  Stream<SpeechRecorderException> get errorStream => _platform.errorStream;

  /// Returns the underlying Android OS release and SDK version.
  Future<String?> getPlatformVersion() {
    return _platform.getPlatformVersion();
  }

  /// Inspects device speech recognition capabilities, audio source injection support,
  /// and offline language model availability.
  Future<RecognitionCapability> checkCapabilities() {
    return _platform.checkCapabilities();
  }

  /// Initializes the speech recording and recognition subsystems.
  ///
  /// [language]: BCP-47 language tag (default: 'en-IN', supports 'en-US', 'en-GB', etc.).
  /// [sampleRate]: PCM sample rate in Hz (default: 16000).
  /// [recognitionMode]: offlineOnly, onlineAllowed, or automatic.
  /// [enablePunctuation]: Automatic capitalization and sentence boundary punctuation.
  /// [enableSegmented]: Enables Android segmented session recognition where supported.
  Future<bool> initialize({
    String language = 'en-IN',
    int sampleRate = 16000,
    RecognitionMode recognitionMode = RecognitionMode.automatic,
    bool enablePunctuation = true,
    bool enableSegmented = true,
  }) {
    return _platform.initialize(
      language: language,
      sampleRate: sampleRate,
      recognitionMode: recognitionMode,
      enablePunctuation: enablePunctuation,
      enableSegmented: enableSegmented,
    );
  }

  /// Starts the single-microphone capture session, begins writing audio to a local WAV file,
  /// and initiates live speech transcription.
  ///
  /// [outputPath]: Optional custom file path where the WAV file will be written.
  /// Returns the absolute path of the audio file being recorded.
  Future<String> start({String? outputPath}) {
    return _platform.start(outputPath: outputPath);
  }

  /// Pauses the audio recording and temporarily pauses speech recognition.
  Future<bool> pause() {
    return _platform.pause();
  }

  /// Resumes recording and speech recognition from where it was paused.
  Future<bool> resume() {
    return _platform.resume();
  }

  /// Stops audio capture, finalizes the WAV file header on disk, merges any pending
  /// transcription segments, and returns the final [RecordingResult].
  Future<RecordingResult> stop() {
    return _platform.stop();
  }

  /// Cancels the recording session and deletes the temporary audio file.
  Future<bool> cancel() {
    return _platform.cancel();
  }

  /// Plays the recorded audio file using the native Android MediaPlayer.
  ///
  /// [filePath]: Path to the audio file to play (defaults to the last recorded file).
  Future<bool> play({String? filePath}) {
    return _platform.play(filePath: filePath);
  }

  /// Pauses the active audio playback.
  Future<bool> pausePlayback() {
    return _platform.pausePlayback();
  }

  /// Resumes the paused audio playback.
  Future<bool> resumePlayback() {
    return _platform.resumePlayback();
  }

  /// Stops audio playback and resets the player to the beginning.
  Future<bool> stopPlayback() {
    return _platform.stopPlayback();
  }

  /// Seeks the audio playback to the specified [position].
  Future<bool> seekTo(Duration position) {
    return _platform.seekTo(position);
  }

  /// Sets the playback speed (typically between 0.5x and 2.0x).
  Future<bool> setPlaybackSpeed(double speed) {
    return _platform.setPlaybackSpeed(speed);
  }

  /// Retrieves the current audio playback position.
  Future<Duration> getPlaybackPosition() {
    return _platform.getPlaybackPosition();
  }

  /// Retrieves the total duration of the audio file currently loaded in the player.
  Future<Duration> getPlaybackDuration() {
    return _platform.getPlaybackDuration();
  }

  /// Releases all native audio and speech recognition resources.
  Future<bool> dispose() {
    return _platform.dispose();
  }
}
