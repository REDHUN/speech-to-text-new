import 'dart:async';

import 'live_speech_recorder_platform_interface.dart';
import 'src/models/playback_progress.dart';
import 'src/models/playback_state.dart';
import 'src/models/recognition_capability.dart';
import 'src/models/recognition_mode.dart';
import 'src/models/recording_result.dart';
import 'src/models/recording_state.dart';
import 'src/models/saved_recording.dart';
import 'src/models/speech_recorder_exception.dart';
import 'src/models/transcription_result.dart';

export 'src/models/playback_progress.dart';
export 'src/models/playback_state.dart';
export 'src/models/recognition_capability.dart';
export 'src/models/recognition_mode.dart';
export 'src/models/recording_result.dart';
export 'src/models/recording_state.dart';
export 'src/models/saved_recording.dart';
export 'src/models/speech_recorder_exception.dart';
export 'src/models/transcription_result.dart';
export 'src/models/transcription_segment.dart';

/// Single-microphone audio recording with live speech transcription on
/// Android 13 (API 33) and newer.
///
/// Methods report failures by throwing [SpeechRecorderException]. Commands sent
/// while another is in progress run in order.
class LiveSpeechRecorder {
  LiveSpeechRecorderPlatform get _platform => LiveSpeechRecorderPlatform.instance;

  /// Real-time transcription updates.
  Stream<TranscriptionResult> get transcriptionStream => _platform.transcriptionStream;

  /// Recording lifecycle states. A new listener first receives the current state.
  Stream<RecordingState> get recordingStateStream => _platform.recordingStateStream;

  /// Playback lifecycle states. A new listener first receives the current state.
  Stream<PlaybackState> get playbackStateStream => _platform.playbackStateStream;

  /// Playback position updates, about five per second while playing.
  Stream<PlaybackProgress> get playbackProgressStream => _platform.playbackProgressStream;

  /// Microphone RMS amplitude normalized between 0.0 and 1.0.
  Stream<double> get amplitudeStream => _platform.amplitudeStream;

  /// Length of the audio recorded so far, excluding pauses.
  Stream<Duration> get durationStream => _platform.durationStream;

  /// Errors and warnings raised during a session. See
  /// [SpeechRecorderException.isFatal].
  Stream<SpeechRecorderException> get errorStream => _platform.errorStream;

  /// Returns the underlying Android OS release and SDK version.
  Future<String?> getPlatformVersion() {
    return _platform.getPlatformVersion();
  }

  /// Inspects device speech recognition capabilities.
  Future<RecognitionCapability> checkCapabilities() {
    return _platform.checkCapabilities();
  }

  /// Configures the recorder. Throws `ALREADY_RECORDING` during a recording.
  ///
  /// [language]: BCP-47 language tag. Formatting rules apply to English only.
  /// [sampleRate]: PCM sample rate in Hz, from 8000 to 48000.
  /// [recognitionMode]: offlineOnly, onlineAllowed, or automatic.
  /// [enablePunctuation]: Adds sentence punctuation when the recognizer gave none.
  /// [enableSegmented]: Keeps one recognition session open for the whole recording.
  /// [enableBackgroundRecording]: Runs a foreground service with a notification
  /// so that recording continues when the app is in the background.
  /// [notificationTitle] and [notificationText]: Content of that notification.
  Future<bool> initialize({
    String language = 'en-IN',
    int sampleRate = 16000,
    RecognitionMode recognitionMode = RecognitionMode.automatic,
    bool enablePunctuation = true,
    bool enableSegmented = true,
    bool enableBackgroundRecording = true,
    String notificationTitle = 'Recording audio',
    String notificationText = 'Microphone is in use',
  }) {
    return _platform.initialize(
      language: language,
      sampleRate: sampleRate,
      recognitionMode: recognitionMode,
      enablePunctuation: enablePunctuation,
      enableSegmented: enableSegmented,
      enableBackgroundRecording: enableBackgroundRecording,
      notificationTitle: notificationTitle,
      notificationText: notificationText,
    );
  }

  /// Opens the microphone and begins recording and transcribing.
  ///
  /// Completes once the microphone is capturing, and throws if it cannot be
  /// opened. An existing file at [outputPath] is replaced.
  ///
  /// Returns the absolute path of the audio file being recorded.
  Future<String> start({String? outputPath}) {
    return _platform.start(outputPath: outputPath);
  }

  /// Pauses recording and closes the microphone. Returns false if not recording.
  Future<bool> pause() {
    return _platform.pause();
  }

  /// Reopens the microphone and continues. Returns false if not paused.
  Future<bool> resume() {
    return _platform.resume();
  }

  /// Stops recording, waits briefly for final transcription, and returns the result.
  ///
  /// Calling this again returns the same result. Throws `NO_RECORDING` if there
  /// is nothing to return.
  Future<RecordingResult> stop() {
    return _platform.stop();
  }

  /// Discards the active recording and deletes its file. Returns false, and
  /// deletes nothing, if no recording is active.
  Future<bool> cancel() {
    return _platform.cancel();
  }

  /// Returns the current recording state.
  Future<RecordingState> getRecordingState() {
    return _platform.getRecordingState();
  }

  /// Returns the current playback state.
  Future<PlaybackState> getPlaybackState() {
    return _platform.getPlaybackState();
  }

  /// Lists recordings saved in the plugin's directory, newest first.
  ///
  /// Recordings interrupted by a crash are repaired and included. Files written
  /// to a custom `outputPath` are not listed.
  Future<List<SavedRecording>> listRecordings() {
    return _platform.listRecordings();
  }

  /// Deletes a recording from the plugin's directory. Returns false if [path]
  /// is outside that directory, is being recorded, or does not exist.
  Future<bool> deleteRecording(String path) {
    return _platform.deleteRecording(path);
  }

  /// Plays an audio file, by default the last recording.
  ///
  /// Throws `RECORDING_ACTIVE` during a recording. Returns false if playback
  /// could not begin, with the reason sent through [errorStream].
  Future<bool> play({String? filePath}) {
    return _platform.play(filePath: filePath);
  }

  /// Pauses playback. Returns false if nothing is playing.
  Future<bool> pausePlayback() {
    return _platform.pausePlayback();
  }

  /// Resumes paused or completed playback. Returns false if that is not possible.
  Future<bool> resumePlayback() {
    return _platform.resumePlayback();
  }

  /// Stops playback and releases the player.
  Future<bool> stopPlayback() {
    return _platform.stopPlayback();
  }

  /// Seeks playback to [position]. Returns false until the audio has loaded.
  Future<bool> seekTo(Duration position) {
    return _platform.seekTo(position);
  }

  /// Sets the playback speed from 0.5x to 2.0x. The speed is kept for later
  /// playback, and changing it while paused does not resume the audio.
  Future<bool> setPlaybackSpeed(double speed) {
    return _platform.setPlaybackSpeed(speed);
  }

  /// Retrieves the current audio playback position.
  Future<Duration> getPlaybackPosition() {
    return _platform.getPlaybackPosition();
  }

  /// Retrieves the duration of the loaded audio, or zero until it has loaded.
  Future<Duration> getPlaybackDuration() {
    return _platform.getPlaybackDuration();
  }

  /// Releases native resources. An active recording is finalized and kept.
  Future<bool> dispose() {
    return _platform.dispose();
  }
}
