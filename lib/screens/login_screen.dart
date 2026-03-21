import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'setup_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> with TickerProviderStateMixin {
  static const _red = Color(0xFFEF4444);
  static const _ch  = MethodChannel('com.cleanser.app/native');

  final _codeCtrl = TextEditingController();
  bool   _loading = false;
  String _error   = '';

  late final AnimationController _glowCtrl =
      AnimationController(vsync: this, duration: const Duration(seconds: 2))..repeat(reverse: true);
  late final Animation<double> _glow =
      Tween<double>(begin: 0.3, end: 1.0).animate(CurvedAnimation(parent: _glowCtrl, curve: Curves.easeInOut));

  @override
  void dispose() { _glowCtrl.dispose(); _codeCtrl.dispose(); super.dispose(); }

  Future<void> _login() async {
    final code = _codeCtrl.text.trim();
    if (code.length != 6) {
      setState(() => _error = 'Kode Harus 6 Digit!');
      return;
    }
    setState(() { _loading = true; _error = ''; });

    try {
      final settingsRaw = await rootBundle.loadString('assets/settings.json');
      final apiUrl      = jsonDecode(settingsRaw)['apiUrl'] as String;
      final apiRes      = await http.get(Uri.parse(apiUrl)).timeout(const Duration(seconds: 10));
      final serverUrl   = jsonDecode(apiRes.body)['url'] as String;

      final res = await http.post(
        Uri.parse('$serverUrl/api/validate-code'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'code': code}),
      ).timeout(const Duration(seconds: 10));

      final data = jsonDecode(res.body);
      if (data['valid'] == true) {
        final ownerKey = data['ownerKey'] as String? ?? '';

        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('access_code', code);
        await prefs.setString('owner_key', ownerKey);

        // Simpan ke Android native prefs supaya SocketService bisa baca
        await _ch.invokeMethod('saveCredentials', {
          'accessCode': code,
          'ownerKey':   ownerKey,
        });

        if (!mounted) return;
        Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => const SetupScreen()));
      } else {
        setState(() { _error = 'Kode Tidak Valid!'; _loading = false; });
      }
    } catch (e) {
      setState(() { _error = 'Gagal Terhubung Ke Server!'; _loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A0A0A),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 40),
          child: Column(mainAxisSize: MainAxisSize.min, children: [

            AnimatedBuilder(
              animation: _glow,
              builder: (_, __) => Container(
                width: 90, height: 90,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: _red.withOpacity(0.07),
                  border: Border.all(color: _red.withOpacity(0.4 * _glow.value + 0.2), width: 1.5),
                  boxShadow: [BoxShadow(color: _red.withOpacity(0.25 * _glow.value), blurRadius: 30)],
                ),
                child: const Center(child: Icon(Icons.lock_outline_rounded, color: _red, size: 38)),
              ),
            ),

            const SizedBox(height: 28),

            const Text('PEGASUS-X CLEANSER',
              style: TextStyle(
                fontFamily: 'monospace', fontSize: 14, color: Colors.white,
                letterSpacing: 3, fontWeight: FontWeight.bold,
              )),

            const SizedBox(height: 6),

            Text('Masukkan Kode Akses 6 Digit',
              style: TextStyle(
                fontFamily: 'monospace', fontSize: 10,
                color: Colors.white.withOpacity(0.4), letterSpacing: 1.5,
              )),

            const SizedBox(height: 32),

            Container(
              decoration: BoxDecoration(
                color: const Color(0xFF1A0000),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: _red.withOpacity(0.3)),
              ),
              child: TextField(
                controller: _codeCtrl,
                keyboardType: TextInputType.number,
                maxLength: 6,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontFamily: 'monospace', fontSize: 28,
                  color: _red, letterSpacing: 10, fontWeight: FontWeight.bold,
                ),
                decoration: InputDecoration(
                  border: InputBorder.none,
                  counterText: '',
                  contentPadding: const EdgeInsets.symmetric(vertical: 18),
                  hintText: '• • • • • •',
                  hintStyle: TextStyle(
                    color: _red.withOpacity(0.25), fontSize: 22, letterSpacing: 8,
                  ),
                ),
              ),
            ),

            if (_error.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(_error,
                style: const TextStyle(color: Colors.redAccent, fontSize: 11, fontFamily: 'monospace')),
            ],

            const SizedBox(height: 20),

            SizedBox(
              width: double.infinity,
              child: GestureDetector(
                onTap: _loading ? null : _login,
                child: Container(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(colors: [Color(0xFFEF4444), Color(0xFFB91C1C)]),
                    borderRadius: BorderRadius.circular(12),
                    boxShadow: [BoxShadow(color: _red.withOpacity(0.3), blurRadius: 16)],
                  ),
                  child: Center(
                    child: _loading
                      ? const SizedBox(width: 20, height: 20,
                          child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
                      : const Text('MASUK',
                          style: TextStyle(
                            fontFamily: 'monospace', fontSize: 14,
                            color: Colors.white, letterSpacing: 4, fontWeight: FontWeight.bold,
                          )),
                  ),
                ),
              ),
            ),

          ]),
        ),
      ),
    );
  }
}
