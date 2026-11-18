/// Intelligent sentence boundary detection, punctuation restoration, and capitalization engine.
class SentenceFormatter {
  final bool enablePunctuation;
  final bool enableCapitalization;

  /// Dedicated interrogative words that strongly suggest a question.
  static const Set<String> _interrogativeWhWords = {
    'who',
    'what',
    'where',
    'when',
    'why',
    'how',
    'which',
    'whose',
    'whom',
  };

  /// Auxiliary verbs that create questions when placed at the head of a clause before a subject.
  static const Set<String> _questionAuxiliaries = {
    'is',
    'are',
    'am',
    'was',
    'were',
    'do',
    'does',
    'did',
    'can',
    'could',
    'would',
    'should',
    'will',
    'shall',
    'has',
    'have',
    'had',
    'may',
    'might',
  };

  static const Set<String> _transitionPhrases = {
    'however',
    'therefore',
    'furthermore',
    'moreover',
    'in addition',
    'for example',
    'for instance',
    'firstly',
    'secondly',
    'thirdly',
    'finally',
    'on the other hand',
    'as a result',
    'consequently',
    'meanwhile',
    'today',
  };

  static const Map<String, String> _commonAcronyms = {
    'ai': 'AI',
    'ml': 'ML',
    'cpu': 'CPU',
    'ram': 'RAM',
    'gpu': 'GPU',
    'os': 'OS',
    'pc': 'PC',
    'usb': 'USB',
    'api': 'API',
    'url': 'URL',
    'html': 'HTML',
    'css': 'CSS',
    'sdk': 'SDK',
    'sql': 'SQL',
    'iot': 'IoT',
    'nlp': 'NLP',
    'llm': 'LLM',
    'http': 'HTTP',
    'https': 'HTTPS',
    'json': 'JSON',
    'xml': 'XML',
  };

  static const Set<String> _properNouns = {
    // Days
    'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday',
    // Months
    'january', 'february', 'march', 'april', 'may', 'june',
    'july', 'august', 'september', 'october', 'november', 'december',
    // Tech platforms
    'android', 'google', 'flutter', 'kotlin', 'java', 'python',
    // Countries & Regions
    'india', 'america', 'britain', 'england', 'china', 'japan',
    'germany', 'france', 'canada', 'australia', 'delhi', 'mumbai', 'bangalore', 'london', 'new york',
    // Common student names
    'rahul', 'priya', 'amit', 'rohan', 'neha', 'alex', 'john', 'sarah', 'michael', 'david',
  };

  const SentenceFormatter({
    this.enablePunctuation = true,
    this.enableCapitalization = true,
  });

  /// Formats raw speech text into well-formed sentences with punctuation and capitalization.
  String formatText(
    String rawText, {
    bool isFinal = true,
    int silenceDurationMs = 0,
  }) {
    final trimmed = rawText.trim();
    if (trimmed.isEmpty) return '';

    final words = trimmed.split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
    if (words.isEmpty) return '';

    final sentences = <List<String>>[];
    var currentSentenceWords = <String>[];

    for (var i = 0; i < words.length; i++) {
      final word = words[i];
      currentSentenceWords.add(word);

      final hasTrailingPunctuation = word.endsWith('.') || word.endsWith('?') || word.endsWith('!');
      final nextWord = (i + 1 < words.length) ? words[i + 1].toLowerCase() : null;
      final nextNextWord = (i + 2 < words.length) ? words[i + 2].toLowerCase() : null;

      var shouldBreak = hasTrailingPunctuation;

      if (!shouldBreak && nextWord != null && currentSentenceWords.length >= 4) {
        // 1. Break before transition phrases (e.g. "...output. However, we must...")
        if (_transitionPhrases.contains(nextWord)) {
          shouldBreak = true;
        }
        // 2. Break before personal pronoun subject transitions (e.g. "...rahul. I am...")
        else if (nextWord == 'i' && nextNextWord != null &&
            {'am', 'was', 'have', 'had', 'will', 'would', 'can', 'could', 'want', 'think', 'believe', 'feel'}.contains(nextNextWord)) {
          shouldBreak = true;
        }
        // 3. Break before Wh-interrogative question words, unless preceded by a cognitive/communication verb
        else if (_interrogativeWhWords.contains(nextWord) && currentSentenceWords.length >= 4) {
          final lastWordLower = currentSentenceWords.last.toLowerCase().replaceAll(RegExp(r'[^a-z]'), '');
          const subordinateVerbs = {
            'explain', 'know', 'understand', 'learn', 'see', 'show', 'tell',
            'discuss', 'hear', 'wonder', 'ask', 'asked', 'wondering'
          };
          if (!subordinateVerbs.contains(lastWordLower)) {
            shouldBreak = true;
          }
        }
      }

      if (shouldBreak) {
        sentences.add(currentSentenceWords);
        currentSentenceWords = [];
      }
    }

    if (currentSentenceWords.isNotEmpty) {
      sentences.add(currentSentenceWords);
    }

    final formattedSentences = <String>[];

    for (var sIndex = 0; sIndex < sentences.length; sIndex++) {
      final sWords = sentences[sIndex];
      final isLastSentence = (sIndex == sentences.length - 1);
      final formatAsFinal = (!isLastSentence) || isFinal || (silenceDurationMs >= 1200 && sWords.length >= 4);

      final formatted = formatSingleSentence(sWords, isFinal: formatAsFinal);
      if (formatted.isNotEmpty) {
        formattedSentences.add(formatted);
      }
    }

    final separator = (silenceDurationMs >= 3500) ? '\n\n' : ' ';
    return formattedSentences.join(separator);
  }

  /// Formats an individual list of words into a punctuated, capitalized sentence.
  String formatSingleSentence(List<String> words, {required bool isFinal}) {
    if (words.isEmpty) return '';

    final transformedWords = <String>[];

    for (var i = 0; i < words.length; i++) {
      final rawWord = words[i];
      var cleanWord = rawWord.trim().replaceAll(RegExp(r'[.,?!;:]+$'), '');
      final lower = cleanWord.toLowerCase();

      // 1. Acronym check
      if (_commonAcronyms.containsKey(lower)) {
        cleanWord = _commonAcronyms[lower]!;
      }
      // 2. Standalone "I" and contractions
      else if (lower == 'i') {
        cleanWord = 'I';
      } else if (lower.startsWith("i'") || lower.startsWith('i’')) {
        cleanWord = 'I${cleanWord.substring(1)}';
      }
      // 3. Proper noun check
      else if (_properNouns.contains(lower)) {
        cleanWord = cleanWord[0].toUpperCase() + cleanWord.substring(1);
      }

      // 4. First word capitalization
      if (enableCapitalization && i == 0 && cleanWord.isNotEmpty) {
        cleanWord = cleanWord[0].toUpperCase() + cleanWord.substring(1);
      }

      // 5. Add comma after introductory transition word at sentence head
      if (enablePunctuation && i == 0 && _transitionPhrases.contains(lower) && words.length > 2) {
        cleanWord += ',';
      }

      transformedWords.add(cleanWord);
    }

    var result = transformedWords.join(' ');

    // 6. Sentence ending punctuation
    if (enablePunctuation && isFinal) {
      final firstWordLower = words.first.toLowerCase();
      final endsWithPunct = result.endsWith('.') || result.endsWith('?') || result.endsWith('!');

      if (!endsWithPunct) {
        final isQuestion = _interrogativeWhWords.contains(firstWordLower) ||
            (_questionAuxiliaries.contains(firstWordLower) && words.length >= 3);
        result += isQuestion ? '?' : '.';
      }
    }

    return result;
  }
}
