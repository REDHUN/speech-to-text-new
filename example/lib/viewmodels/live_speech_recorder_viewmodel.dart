import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';

/// ViewModel managing state and operations for the LiveSpeechRecorder example application.
class LiveSpeechRecorderViewModel extends ChangeNotifier {
  final LiveSpeechRecorder _recorder = LiveSpeechRecorder();

  // State
  RecordingState _recordingState = RecordingState.uninitialized;
  PlaybackState _playbackState = PlaybackState.idle;

  String _fullTranscription = '';
  String _partialTranscription = '';
  String _confirmedTranscription = '';
  List<TranscriptionSegment> _segments = [];

  Duration _recordingDuration = Duration.zero;
  Duration _playbackPosition = Duration.zero;
  Duration _playbackDuration = Duration.zero;

  double _currentAmplitude = 0.0;
  final List<double> _amplitudeHistory = List.filled(30, 0.05, growable: true);

  RecognitionCapability? _capability;
  RecordingResult? _lastResult;
  String? _errorMessage;

  String _selectedLanguage = 'en-IN';
  RecognitionMode _selectedMode = RecognitionMode.automatic;
  double _playbackSpeed = 1.0;
  bool _isStarting = false;
  bool _isDisposed = false;

  // Stream Subscriptions
  final List<StreamSubscription<Object?>> _subscriptions = [];

  // Getters
  RecordingState get recordingState => _recordingState;
  PlaybackState get playbackState => _playbackState;
  String get fullTranscription => _fullTranscription;
  String get partialTranscription => _partialTranscription;
  String get confirmedTranscription => _confirmedTranscription;
  List<TranscriptionSegment> get segments => _segments;
  Duration get recordingDuration => _recordingDuration;
  Duration get playbackPosition => _playbackPosition;
  Duration get playbackDuration => _playbackDuration;
  double get currentAmplitude => _currentAmplitude;
  List<double> get amplitudeHistory => List.unmodifiable(_amplitudeHistory);
  RecognitionCapability? get capability => _capability;
  RecordingResult? get lastResult => _lastResult;
  String? get errorMessage => _errorMessage;
  String get selectedLanguage => _selectedLanguage;
  RecognitionMode get selectedMode => _selectedMode;
  double get playbackSpeed => _playbackSpeed;

  bool get isStarting => _isStarting;
  bool get isRecording => _recordingState == RecordingState.recording;
  bool get isPaused => _recordingState == RecordingState.paused;
  bool get isPlaying => _playbackState == PlaybackState.playing;

  LiveSpeechRecorderViewModel() {
    _initSubscribers();
    checkCapabilities();
  }

  void _notify() {
    if (!_isDisposed) notifyListeners();
  }

  void _showError(Object error) {
    _errorMessage = error is SpeechRecorderException
        ? '${error.code}: ${error.message}'
        : error.toString();
    _notify();
  }

  void _initSubscribers() {
    _subscriptions.addAll([
      _recorder.transcriptionStream.listen((result) {
        _fullTranscription = result.fullText;
        _partialTranscription = result.partialText;
        _confirmedTranscription = result.confirmedText;
        _segments = result.segments;
        _notify();
      }),
      _recorder.recordingStateStream.listen((state) {
        _recordingState = state;
        _notify();
        if (state == RecordingState.error) _loadResultAfterFailure();
      }),
      _recorder.playbackStateStream.listen((state) {
        _playbackState = state;
        if (state == PlaybackState.idle) _playbackPosition = Duration.zero;
        _notify();
      }),
      _recorder.playbackProgressStream.listen((progress) {
        _playbackPosition = progress.position;
        if (progress.duration > Duration.zero) {
          _playbackDuration = progress.duration;
        }
        _notify();
      }),
      _recorder.amplitudeStream.listen((amp) {
        _currentAmplitude = amp;
        _amplitudeHistory.removeAt(0);
        _amplitudeHistory.add(amp.clamp(0.05, 1.0));
        _notify();
      }),
      _recorder.durationStream.listen((dur) {
        _recordingDuration = dur;
        _notify();
      }),
      _recorder.errorStream.listen((error) {
        if (error.code == 'MIC_RESTORED') {
          clearError();
        } else {
          _showError(error);
        }
      }),
    ]);
  }

  /// A fatal error ends the recording, but the audio captured so far is saved.
  Future<void> _loadResultAfterFailure() async {
    try {
      _applyResult(await _recorder.stop());
    } on SpeechRecorderException catch (e) {
      _showError(e);
    }
  }

  void _applyResult(RecordingResult result) {
    _lastResult = result;
    _fullTranscription = result.transcription;
    _partialTranscription = '';
    _recordingDuration = result.duration;
    _playbackDuration = result.duration;
    _playbackPosition = Duration.zero;
    _notify();
  }

  void _clearSession() {
    _lastResult = null;
    _fullTranscription = '';
    _partialTranscription = '';
    _confirmedTranscription = '';
    _segments = [];
    _recordingDuration = Duration.zero;
    _playbackPosition = Duration.zero;
    _playbackDuration = Duration.zero;
    _amplitudeHistory.fillRange(0, _amplitudeHistory.length, 0.05);
  }

  Future<void> checkCapabilities() async {
    try {
      _capability = await _recorder.checkCapabilities();
      _notify();
    } on SpeechRecorderException catch (e) {
      _showError(e);
    }
  }

  void setLanguage(String lang) {
    if (_selectedLanguage != lang) {
      _selectedLanguage = lang;
      _notify();
    }
  }

  void setRecognitionMode(RecognitionMode mode) {
    if (_selectedMode != mode) {
      _selectedMode = mode;
      _notify();
    }
  }

  Future<void> startRecording() async {
    if (_isStarting || isRecording || isPaused) return;

    _isStarting = true;
    _errorMessage = null;
    _notify();

    try {
      final status = await Permission.microphone.request();
      if (!status.isGranted) {
        _errorMessage = 'Microphone permission was denied. Please grant microphone access to record audio.';
        return;
      }
      // Without this, the background recording notification stays hidden.
      await Permission.notification.request();

      _clearSession();
      await _recorder.initialize(
        language: _selectedLanguage,
        sampleRate: 16000,
        recognitionMode: _selectedMode,
        enablePunctuation: true,
        enableSegmented: true,
        notificationTitle: 'Live Speech Recorder',
        notificationText: 'Recording your answer',
      );
      await _recorder.start();
    } on SpeechRecorderException catch (e) {
      _showError(e);
    } finally {
      _isStarting = false;
      _notify();
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    try {
      await action();
    } on SpeechRecorderException catch (e) {
      _showError(e);
    }
  }

  Future<void> pauseRecording() => _run(_recorder.pause);

  Future<void> resumeRecording() => _run(_recorder.resume);

  Future<void> stopRecording() => _run(() async {
        _applyResult(await _recorder.stop());
      });

  Future<void> cancelRecording() => _run(() async {
        await _recorder.cancel();
        _clearSession();
        _notify();
      });

  // Playback Operations
  Future<void> playAudio() => _run(() async {
        if (isRecording || isPaused) {
          _errorMessage = 'Please stop recording before playing audio.';
          _notify();
          return;
        }
        final result = _lastResult;
        if (result == null) {
          _errorMessage = 'No recorded audio available to play.';
          _notify();
          return;
        }
        if (_playbackState == PlaybackState.paused) {
          await _recorder.resumePlayback();
        } else {
          await _recorder.play(filePath: result.audioPath);
        }
      });

  Future<void> pauseAudio() => _run(_recorder.pausePlayback);

  Future<void> resumeAudio() => _run(_recorder.resumePlayback);

  Future<void> stopAudio() => _run(_recorder.stopPlayback);

  Future<void> seekAudio(Duration position) => _run(() async {
        if (await _recorder.seekTo(position)) {
          _playbackPosition = position;
          _notify();
        }
      });

  Future<void> setPlaybackSpeed(double speed) => _run(() async {
        await _recorder.setPlaybackSpeed(speed);
        _playbackSpeed = speed;
        _notify();
      });

  void copyTranscriptionToClipboard() {
    if (_fullTranscription.isNotEmpty) {
      Clipboard.setData(ClipboardData(text: _fullTranscription));
    }
  }

  void clearError() {
    _errorMessage = null;
    _notify();
  }

  @override
  void dispose() {
    _isDisposed = true;
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    _recorder.dispose();
    super.dispose();
  }
}
