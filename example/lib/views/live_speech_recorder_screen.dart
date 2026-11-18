import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';
import '../viewmodels/live_speech_recorder_viewmodel.dart';
import 'components/audio_waveform_indicator.dart';
import 'components/playback_control_panel.dart';

class LiveSpeechRecorderScreen extends StatelessWidget {
  const LiveSpeechRecorderScreen({super.key});

  String _formatTimer(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    final ms = (d.inMilliseconds.remainder(1000) ~/ 100).toString();
    return '$m:$s.$ms';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final vm = context.watch<LiveSpeechRecorderViewModel>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Live Speech Transcription & Audio Recording'),
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh Capabilities',
            onPressed: vm.checkCapabilities,
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 1. Device Capabilities & Mode Banner
            _buildCapabilityBanner(context, vm),
            const SizedBox(height: 12),

            // 2. Configuration Selectors
            _buildConfigRow(context, vm),
            const SizedBox(height: 16),

            // 3. Audio Amplitude Waveform & Timer
            Card(
              elevation: 2,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
                            Container(
                              width: 12,
                              height: 12,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: vm.isRecording
                                    ? Colors.red
                                    : vm.isPaused
                                        ? Colors.amber
                                        : Colors.grey,
                              ),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              vm.recordingState.name.toUpperCase(),
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: vm.isRecording
                                    ? Colors.red
                                    : vm.isPaused
                                        ? Colors.amber.shade800
                                        : Colors.grey,
                              ),
                            ),
                          ],
                        ),
                        Text(
                          _formatTimer(vm.recordingDuration),
                          style: theme.textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.bold,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    AudioWaveformIndicator(
                      amplitudeHistory: vm.amplitudeHistory,
                      isRecording: vm.isRecording,
                      isPaused: vm.isPaused,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // 4. Recording Controls
            _buildControlButtons(context, vm),
            const SizedBox(height: 16),

            // Error Banner
            if (vm.errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.red.shade50,
                  border: Border.all(color: Colors.red.shade300),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.error_outline, color: Colors.red),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        vm.errorMessage!,
                        style: const TextStyle(color: Colors.red, fontSize: 13),
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.close, size: 16, color: Colors.red),
                      onPressed: vm.clearError,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            // 5. Live Transcription Display
            _buildTranscriptionCard(context, vm),
            const SizedBox(height: 16),

            // 6. Playback Control Panel (appears after recording is stopped)
            if (!vm.isRecording && !vm.isPaused && vm.lastResult != null) ...[
              PlaybackControlPanel(viewModel: vm),
              const SizedBox(height: 16),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildCapabilityBanner(BuildContext context, LiveSpeechRecorderViewModel vm) {
    final theme = Theme.of(context);
    final cap = vm.capability;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.primaryContainer.withValues(alpha: 0.4),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: theme.colorScheme.primary.withValues(alpha: 0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.verified_user_outlined, size: 18, color: theme.colorScheme.primary),
              const SizedBox(width: 6),
              Text(
                'Single-Mic Capture & Android Capabilities',
                style: theme.textTheme.labelMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colorScheme.primary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              _buildStatusPill(
                'Single AudioRecord Pipe',
                cap?.supportsAudioSourceInjection == true,
                'API 33+ EXTRA_AUDIO_SOURCE',
              ),
              _buildStatusPill(
                'Offline ASR Model',
                cap?.supportsOfflineRecognition == true,
                'On-Device Recognizer',
              ),
              _buildStatusPill(
                'SpeechRecognizer',
                cap?.hasSpeechRecognizer == true,
                'Installed',
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildStatusPill(String title, bool active, String note) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: active ? Colors.green.shade50 : Colors.orange.shade50,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: active ? Colors.green.shade300 : Colors.orange.shade300),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            active ? Icons.check_circle : Icons.info,
            size: 14,
            color: active ? Colors.green.shade700 : Colors.orange.shade700,
          ),
          const SizedBox(width: 4),
          Text(
            '$title ($note)',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: active ? Colors.green.shade900 : Colors.orange.shade900,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildConfigRow(BuildContext context, LiveSpeechRecorderViewModel vm) {
    return Row(
      children: [
        // Language Dropdown
        Expanded(
          child: DropdownButtonFormField<String>(
            decoration: InputDecoration(
              labelText: 'Language',
              isDense: true,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
            initialValue: vm.selectedLanguage,
            items: const [
              DropdownMenuItem(value: 'en-IN', child: Text('English (India)')),
              DropdownMenuItem(value: 'en-US', child: Text('English (US)')),
              DropdownMenuItem(value: 'en-GB', child: Text('English (UK)')),
            ],
            onChanged: vm.isRecording ? null : (val) {
              if (val != null) vm.setLanguage(val);
            },
          ),
        ),
        const SizedBox(width: 12),

        // Mode Dropdown
        Expanded(
          child: DropdownButtonFormField<RecognitionMode>(
            decoration: InputDecoration(
              labelText: 'Recognition Mode',
              isDense: true,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
            initialValue: vm.selectedMode,
            items: const [
              DropdownMenuItem(
                value: RecognitionMode.offlineOnly,
                child: Text('Offline Only'),
              ),
              DropdownMenuItem(
                value: RecognitionMode.automatic,
                child: Text('Automatic'),
              ),
              DropdownMenuItem(
                value: RecognitionMode.onlineAllowed,
                child: Text('Online Allowed'),
              ),
            ],
            onChanged: vm.isRecording ? null : (val) {
              if (val != null) vm.setRecognitionMode(val);
            },
          ),
        ),
      ],
    );
  }

  Widget _buildControlButtons(BuildContext context, LiveSpeechRecorderViewModel vm) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        // Start / Stop Main Action
        if (!vm.isRecording && !vm.isPaused)
          Expanded(
            child: FilledButton.icon(
              icon: const Icon(Icons.mic),
              label: const Text('Start Recording & Transcribing'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: vm.isStarting ? null : vm.startRecording,
            ),
          )
        else ...[
          // Pause / Resume
          OutlinedButton.icon(
            icon: Icon(vm.isPaused ? Icons.play_arrow : Icons.pause),
            label: Text(vm.isPaused ? 'Resume' : 'Pause'),
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            onPressed: vm.isPaused ? vm.resumeRecording : vm.pauseRecording,
          ),
          const SizedBox(width: 12),

          // Stop
          Expanded(
            child: FilledButton.icon(
              icon: const Icon(Icons.stop),
              label: const Text('Stop & Save'),
              style: FilledButton.styleFrom(
                backgroundColor: Colors.red.shade700,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: vm.stopRecording,
            ),
          ),
          const SizedBox(width: 12),

          // Cancel
          IconButton.outlined(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Cancel & Discard',
            onPressed: vm.cancelRecording,
          ),
        ],
      ],
    );
  }

  Widget _buildTranscriptionCard(BuildContext context, LiveSpeechRecorderViewModel vm) {
    final theme = Theme.of(context);

    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.subtitles, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text(
                  'Live & Formatted Transcription',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.copy, size: 20),
                  tooltip: 'Copy Transcription',
                  onPressed: vm.fullTranscription.isNotEmpty
                      ? () {
                          vm.copyTranscriptionToClipboard();
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                              content: Text('Transcription copied to clipboard!'),
                              duration: Duration(seconds: 2),
                            ),
                          );
                        }
                      : null,
                ),
              ],
            ),
            const Divider(),
            const SizedBox(height: 8),

            // Transcript Body
            Container(
              constraints: const BoxConstraints(minHeight: 140, maxHeight: 300),
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(12),
              ),
              child: SingleChildScrollView(
                child: vm.fullTranscription.isEmpty
                    ? Center(
                        child: Text(
                          vm.isRecording
                              ? 'Listening... Start speaking your answer or explanation.'
                              : 'Press "Start Recording" to begin live speech-to-text.',
                          style: TextStyle(
                            color: Colors.grey.shade500,
                            fontStyle: FontStyle.italic,
                          ),
                        ),
                      )
                    : RichText(
                        text: TextSpan(
                          style: theme.textTheme.bodyLarge?.copyWith(
                            height: 1.5,
                            fontSize: 16,
                          ),
                          children: [
                            // Confirmed sentences
                            TextSpan(
                              text: vm.confirmedTranscription,
                              style: TextStyle(
                                color: theme.colorScheme.onSurface,
                              ),
                            ),
                            if (vm.confirmedTranscription.isNotEmpty && vm.partialTranscription.isNotEmpty)
                              const TextSpan(text: ' '),
                            // Live partial preview
                            if (vm.partialTranscription.isNotEmpty)
                              TextSpan(
                                text: vm.partialTranscription,
                                style: TextStyle(
                                  color: theme.colorScheme.primary,
                                  fontStyle: FontStyle.italic,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                          ],
                        ),
                      ),
              ),
            ),
            const SizedBox(height: 12),

            // Segment Count & Hint
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Flexible(
                  child: Text(
                    '${vm.segments.length} confirmed segments',
                    style: theme.textTheme.bodySmall?.copyWith(color: Colors.grey.shade600),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    'Auto-punctuation & deduplication active',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: Colors.green.shade700,
                      fontWeight: FontWeight.w500,
                    ),
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.end,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
