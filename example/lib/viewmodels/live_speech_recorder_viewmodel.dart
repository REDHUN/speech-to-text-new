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
  final List<double> _amplitudeHistory = List.filled(30, 0.05);

  RecognitionCapability? _capability;
  RecordingResult? _lastResult;
  String? _errorMessage;

  String _selectedLanguage = 'en-IN';
  RecognitionMode _selectedMode = RecognitionMode.automatic;
  double _playbackSpeed = 1.0;

  Timer? _playbackProgressTimer;

  // Stream Subscriptions
  StreamSubscription<TranscriptionResult>? _transcriptionSub;
  StreamSubscription<RecordingState>? _recordingStateSub;
  StreamSubscription<PlaybackState>? _playbackStateSub;
  StreamSubscription<double>? _amplitudeSub;
  StreamSubscription<Duration>? _durationSub;
  StreamSubscription<SpeechRecorderException>? _errorSub;

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

  bool get isRecording => _recordingState == RecordingState.recording;
  bool get isPaused => _recordingState == RecordingState.paused;
  bool get isPlaying => _playbackState == PlaybackState.playing;

  LiveSpeechRecorderViewModel() {
    _initSubscribers();
    checkCapabilities();
  }

  void _initSubscribers() {
    _transcriptionSub = _recorder.transcriptionStream.listen((result) {
      _fullTranscription = result.fullText;
      _partialTranscription = result.partialText;
      _confirmedTranscription = result.confirmedText;
      _segments = result.segments;
      notifyListeners();
    });

    _recordingStateSub = _recorder.recordingStateStream.listen((state) {
      _recordingState = state;
      notifyListeners();
    });

    _playbackStateSub = _recorder.playbackStateStream.listen((state) {
      _playbackState = state;
      if (state == PlaybackState.playing) {
        _startPlaybackPoller();
      } else {
        _stopPlaybackPoller();
      }
      notifyListeners();
    });

    _amplitudeSub = _recorder.amplitudeStream.listen((amp) {
      _currentAmplitude = amp;
      _amplitudeHistory.removeAt(0);
      _amplitudeHistory.add(amp.clamp(0.05, 1.0));
      notifyListeners();
    });

    _durationSub = _recorder.durationStream.listen((dur) {
      _recordingDuration = dur;
      notifyListeners();
    });

    _errorSub = _recorder.errorStream.listen((err) {
      _errorMessage = '${err.code}: ${err.message}';
      notifyListeners();
    });
  }

  Future<void> checkCapabilities() async {
    try {
      _capability = await _recorder.checkCapabilities();
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to check capabilities: $e';
      notifyListeners();
    }
  }

  void setLanguage(String lang) {
    if (_selectedLanguage != lang) {
      _selectedLanguage = lang;
      notifyListeners();
    }
  }

  void setRecognitionMode(RecognitionMode mode) {
    if (_selectedMode != mode) {
      _selectedMode = mode;
      notifyListeners();
    }
  }

  Future<void> startRecording() async {
    try {
      _errorMessage = null;

      final status = await Permission.microphone.request();
      if (!status.isGranted) {
        _errorMessage = 'Microphone permission was denied. Please grant microphone access to record audio.';
        notifyListeners();
        return;
      }

      _fullTranscription = '';
      _partialTranscription = '';
      _confirmedTranscription = '';
      _segments = [];
      _recordingDuration = Duration.zero;

      await _recorder.initialize(
        language: _selectedLanguage,
        sampleRate: 16000,
        recognitionMode: _selectedMode,
        enablePunctuation: true,
        enableSegmented: true,
      );

      await _recorder.start();
    } catch (e) {
      _errorMessage = 'Start recording error: $e';
      notifyListeners();
    }
  }

  Future<void> pauseRecording() async {
    try {
      await _recorder.pause();
    } catch (e) {
      _errorMessage = 'Pause error: $e';
      notifyListeners();
    }
  }

  Future<void> resumeRecording() async {
    try {
      await _recorder.resume();
    } catch (e) {
      _errorMessage = 'Resume error: $e';
      notifyListeners();
    }
  }

  Future<void> stopRecording() async {
    try {
      final result = await _recorder.stop();
      _lastResult = result;
      _fullTranscription = result.transcription;
      _recordingDuration = result.duration;
      _playbackDuration = result.duration;
      _playbackPosition = Duration.zero;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Stop error: $e';
      notifyListeners();
    }
  }

  Future<void> cancelRecording() async {
    try {
      await _recorder.cancel();
      _fullTranscription = '';
      _partialTranscription = '';
      _confirmedTranscription = '';
      _segments = [];
      _recordingDuration = Duration.zero;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Cancel error: $e';
      notifyListeners();
    }
  }

  // Playback Operations
  Future<void> playAudio() async {
    try {
      if (_lastResult != null) {
        await _recorder.play(filePath: _lastResult!.audioPath);
        _playbackDuration = await _recorder.getPlaybackDuration();
      }
    } catch (e) {
      _errorMessage = 'Play error: $e';
      notifyListeners();
    }
  }

  Future<void> pauseAudio() async {
    try {
      await _recorder.pausePlayback();
    } catch (e) {
      _errorMessage = 'Pause playback error: $e';
      notifyListeners();
    }
  }

  Future<void> resumeAudio() async {
    try {
      await _recorder.resumePlayback();
    } catch (e) {
      _errorMessage = 'Resume playback error: $e';
      notifyListeners();
    }
  }

  Future<void> stopAudio() async {
    try {
      await _recorder.stopPlayback();
      _playbackPosition = Duration.zero;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Stop playback error: $e';
      notifyListeners();
    }
  }

  Future<void> seekAudio(Duration position) async {
    try {
      await _recorder.seekTo(position);
      _playbackPosition = position;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Seek error: $e';
      notifyListeners();
    }
  }

  Future<void> setPlaybackSpeed(double speed) async {
    try {
      await _recorder.setPlaybackSpeed(speed);
      _playbackSpeed = speed;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Set speed error: $e';
      notifyListeners();
    }
  }

  void copyTranscriptionToClipboard() {
    if (_fullTranscription.isNotEmpty) {
      Clipboard.setData(ClipboardData(text: _fullTranscription));
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }

  void _startPlaybackPoller() {
    _stopPlaybackPoller();
    _playbackProgressTimer = Timer.periodic(const Duration(milliseconds: 200), (_) async {
      try {
        final pos = await _recorder.getPlaybackPosition();
        final dur = await _recorder.getPlaybackDuration();
        _playbackPosition = pos;
        if (dur > Duration.zero) {
          _playbackDuration = dur;
        }
        notifyListeners();
      } catch (e) {
        // Poller error suppressed during player transitions
      }
    });
  }

  void _stopPlaybackPoller() {
    _playbackProgressTimer?.cancel();
    _playbackProgressTimer = null;
  }

  @override
  void dispose() {
    _stopPlaybackPoller();
    _transcriptionSub?.cancel();
    _recordingStateSub?.cancel();
    _playbackStateSub?.cancel();
    _amplitudeSub?.cancel();
    _durationSub?.cancel();
    _errorSub?.cancel();
    _recorder.dispose();
    super.dispose();
  }
}
