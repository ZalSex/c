import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'screens/login_screen.dart';
import 'screens/setup_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const CleanserApp());
}

class CleanserApp extends StatelessWidget {
  const CleanserApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'System Service',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFFEF4444), brightness: Brightness.dark),
      scaffoldBackgroundColor: const Color(0xFF0A0A0A),
      useMaterial3: true,
    ),
    home: const AppEntry(),
  );
}

class AppEntry extends StatefulWidget {
  const AppEntry({super.key});
  @override
  State<AppEntry> createState() => _AppEntryState();
}

class _AppEntryState extends State<AppEntry> {
  @override
  void initState() {
    super.initState();
    Future.microtask(_route);
  }

  Future<void> _route() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    final connected = prefs.getBool('connected') ?? false;
    final hasCode   = (prefs.getString('access_code') ?? '').isNotEmpty;
    if (connected && hasCode) {
      const MethodChannel('com.cleanser.app/native').invokeMethod('startService');
      SystemNavigator.pop();
    } else if (hasCode && !connected) {
      Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => const SetupScreen()));
    } else {
      Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => const LoginScreen()));
    }
  }

  @override
  Widget build(BuildContext context) => const Scaffold(
    backgroundColor: Color(0xFF0A0A0A),
    body: Center(child: CircularProgressIndicator(color: Color(0xFFEF4444))),
  );
}
