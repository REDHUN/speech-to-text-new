import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'viewmodels/live_speech_recorder_viewmodel.dart';
import 'views/live_speech_recorder_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LiveSpeechRecorderExampleApp());
}

class LiveSpeechRecorderExampleApp extends StatelessWidget {
  const LiveSpeechRecorderExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => LiveSpeechRecorderViewModel()),
      ],
      child: MaterialApp(
        title: 'Live Speech Recorder & Transcription',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.deepPurple,
            brightness: Brightness.light,
          ),
          cardTheme: CardThemeData(
            elevation: 1.5,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
        darkTheme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
            seedColor: Colors.deepPurple,
            brightness: Brightness.dark,
          ),
          cardTheme: CardThemeData(
            elevation: 1.5,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),
        themeMode: ThemeMode.system,
        home: const LiveSpeechRecorderScreen(),
      ),
    );
  }
}
