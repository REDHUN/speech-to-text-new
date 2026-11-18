import 'package:flutter/material.dart';
import '../../viewmodels/live_speech_recorder_viewmodel.dart';

class PlaybackControlPanel extends StatelessWidget {
  final LiveSpeechRecorderViewModel viewModel;

  const PlaybackControlPanel({
    super.key,
    required this.viewModel,
  });

  String _formatDuration(Duration duration) {
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final lastResult = viewModel.lastResult;
    if (lastResult == null) return const SizedBox.shrink();

    final maxDurationMs = viewModel.playbackDuration.inMilliseconds.toDouble();
    final currentPosMs = viewModel.playbackPosition.inMilliseconds.toDouble().clamp(0.0, maxDurationMs > 0 ? maxDurationMs : 1.0);

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
                Icon(Icons.audiotrack, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Text(
                  'Recorded Audio Playback',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    '${(lastResult.fileSizeBytes / 1024).toStringAsFixed(1)} KB (WAV)',
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onPrimaryContainer,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Progress Slider
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                trackHeight: 4,
              ),
              child: Slider(
                value: currentPosMs,
                min: 0.0,
                max: maxDurationMs > 0 ? maxDurationMs : 1.0,
                onChanged: (val) {
                  viewModel.seekAudio(Duration(milliseconds: val.toInt()));
                },
              ),
            ),

            // Timestamps
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8.0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    _formatDuration(viewModel.playbackPosition),
                    style: theme.textTheme.bodySmall,
                  ),
                  Text(
                    _formatDuration(viewModel.playbackDuration),
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),

            // Playback buttons & Speed selector
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Stop Button
                IconButton(
                  icon: const Icon(Icons.stop),
                  onPressed: viewModel.stopAudio,
                  tooltip: 'Stop Playback',
                ),
                const SizedBox(width: 12),

                // Play / Pause Toggle Button
                FilledButton.tonal(
                  onPressed: () {
                    if (viewModel.isPlaying) {
                      viewModel.pauseAudio();
                    } else {
                      viewModel.playAudio();
                    }
                  },
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(viewModel.isPlaying ? Icons.pause : Icons.play_arrow),
                      const SizedBox(width: 6),
                      Text(viewModel.isPlaying ? 'Pause' : 'Play Audio'),
                    ],
                  ),
                ),
                const SizedBox(width: 12),

                // Speed Dropdown
                PopupMenuButton<double>(
                  tooltip: 'Playback Speed',
                  initialValue: viewModel.playbackSpeed,
                  onSelected: (speed) => viewModel.setPlaybackSpeed(speed),
                  itemBuilder: (context) => [0.8, 1.0, 1.25, 1.5, 2.0].map((s) {
                    return PopupMenuItem<double>(
                      value: s,
                      child: Text('${s}x'),
                    );
                  }).toList(),
                  child: Chip(
                    label: Text('${viewModel.playbackSpeed}x'),
                    avatar: const Icon(Icons.speed, size: 16),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),

            // File Path Info
            Text(
              'Saved to: ${lastResult.audioPath}',
              style: theme.textTheme.bodySmall?.copyWith(
                color: Colors.grey.shade600,
                fontSize: 11,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }
}
