import 'package:flutter_test/flutter_test.dart';
import 'package:live_speech_recorder/live_speech_recorder.dart';

void main() {
  group('SentenceFormatter Tests', () {
    const formatter = SentenceFormatter(
      enablePunctuation: true,
      enableCapitalization: true,
    );

    test('Capitalizes first word and proper nouns', () {
      const input = 'my name is rahul i am studying computer science';
      final output = formatter.formatText(input, isFinal: true);
      expect(output, 'My name is Rahul. I am studying computer science.');
    });

    test('Detects and preserves technical acronyms', () {
      const input = 'today we will discuss ai ml and modern cpu architecture';
      final output = formatter.formatText(input, isFinal: true);
      expect(output, 'Today, we will discuss AI ML and modern CPU architecture.');
    });

    test('Applies question mark for questions', () {
      const input = 'how does speech recognition work on android devices';
      final output = formatter.formatText(input, isFinal: true);
      expect(output, 'How does speech recognition work on Android devices?');
    });

    test('Handles sentences with introductory transition phrases with commas', () {
      const input = 'however we must ensure the microphone audio is preserved';
      final output = formatter.formatText(input, isFinal: true);
      expect(output, 'However, we must ensure the microphone audio is preserved.');
    });

    test('Formats long student explanation without corrupting text', () {
      const input =
          'computer is an electronic device that accepts data from the user '
          'processes the information and provides output computers are used in schools '
          'hospitals offices and many other places they make our work easier and faster';

      final output = formatter.formatText(input, isFinal: true);
      expect(output.startsWith('Computer is an electronic device'), isTrue);
      expect(output.endsWith('.'), isTrue);
    });

    test('Respects silence duration for paragraph separation', () {
      const input = 'first paragraph sentence';
      final output = formatter.formatText(input, isFinal: true, silenceDurationMs: 4000);
      expect(output, 'First paragraph sentence.');
    });

    test('Formats partial hypothesis without trailing period if not final', () {
      const input = 'i want to explain';
      final output = formatter.formatText(input, isFinal: false);
      expect(output, 'I want to explain');
    });
  });
}
