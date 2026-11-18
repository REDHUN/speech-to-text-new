import 'package:flutter/material.dart';

/// Animated audio waveform widget representing microphone input amplitude.
class AudioWaveformIndicator extends StatelessWidget {
  final List<double> amplitudeHistory;
  final bool isRecording;
  final bool isPaused;

  const AudioWaveformIndicator({
    super.key,
    required this.amplitudeHistory,
    required this.isRecording,
    required this.isPaused,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final activeColor = isPaused
        ? Colors.amber.shade700
        : isRecording
            ? theme.colorScheme.primary
            : Colors.grey.shade400;

    return Container(
      height: 60,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: activeColor.withValues(alpha: 0.3),
          width: 1.5,
        ),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: amplitudeHistory.map((amplitude) {
          final barHeight = isRecording
              ? (amplitude * 44).clamp(4.0, 44.0)
              : 4.0;

          return AnimatedContainer(
            duration: const Duration(milliseconds: 100),
            curve: Curves.easeOutQuad,
            width: 4,
            height: barHeight,
            decoration: BoxDecoration(
              color: activeColor,
              borderRadius: BorderRadius.circular(2),
            ),
          );
        }).toList(),
      ),
    );
  }
}
