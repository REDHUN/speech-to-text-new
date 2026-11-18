import 'package:flutter_test/flutter_test.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';

void main() {
  group('TranscriptManager Tests', () {
    late TranscriptManager manager;

    setUp(() {
      manager = TranscriptManager();
    });

    test('Deduplicates boundary word overlap across recognition restarts', () {
      manager.setSessionStartTime(0);
      manager.confirmSegment('I want to explain how computers work', 3000);

      expect(manager.getConfirmedText(), 'I want to explain how computers work.');

      // Recognizer session times out and new session begins with overlapping words
      const incomingWithOverlap = 'computers work in schools and hospitals';
      final deduped = manager.deduplicateOverlap(incomingWithOverlap);

      expect(deduped, 'in schools and hospitals');

      manager.confirmSegment(incomingWithOverlap, 6000);
      final fullText = manager.getConfirmedText();

      expect(fullText, 'I want to explain how computers work. In schools and hospitals.');
      // Verify "computers work" appears only once
      final count = RegExp('computers work', caseSensitive: false).allMatches(fullText).length;
      expect(count, 1);
    });

    test('Live partial merging preserves confirmed history', () {
      manager.setSessionStartTime(0);
      manager.confirmSegment('Artificial intelligence is transforming education', 3000);
      manager.updatePartial('it enables personalized learning');

      final liveFull = manager.getFormattedFullText();

      expect(
        liveFull,
        'Artificial intelligence is transforming education. It enables personalized learning',
      );

      // Verify confirmed history is unchanged
      expect(
        manager.getConfirmedText(),
        'Artificial intelligence is transforming education.',
      );
    });

    test('Tracks segment timestamps accurately', () {
      manager.setSessionStartTime(1000);
      final seg1 = manager.confirmSegment('first segment text', 4500);

      expect(seg1, isNotNull);
      expect(seg1!.startTimeMs, 1000);
      expect(seg1.endTimeMs, 4500);
      expect(seg1.startDuration, const Duration(milliseconds: 1000));
      expect(seg1.endDuration, const Duration(milliseconds: 4500));

      final seg2 = manager.confirmSegment('second segment text', 8000);
      expect(seg2, isNotNull);
      expect(seg2!.startTimeMs, 4500);
      expect(seg2.endTimeMs, 8000);

      expect(manager.getSegments().length, 2);
    });

    test('Reset clears all accumulator state', () {
      manager.confirmSegment('test sentence', 2000);
      manager.updatePartial('partial hypothesis');
      expect(manager.getConfirmedText().isNotEmpty, isTrue);

      manager.reset();
      expect(manager.getConfirmedText().isEmpty, isTrue);
      expect(manager.getCurrentPartial().isEmpty, isTrue);
      expect(manager.getSegments().isEmpty, isTrue);
    });
  });
}
