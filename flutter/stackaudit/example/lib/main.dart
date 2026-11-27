import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:http/http.dart' as http;
import 'package:material_ui/material_ui.dart';
import 'package:stack_audit/dio.dart';
import 'package:stack_audit/stack_audit.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await StackAudit.start();
  runApp(const ExampleApp());
}

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key, this.client, this.dio});

  final http.Client? client;
  final Dio? dio;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'StackAudit example',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal)),
      home: HomePage(client: client, dio: dio),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key, this.client, this.dio});

  final http.Client? client;
  final Dio? dio;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  late final http.Client _client = widget.client ?? StackAuditHttpClient(http.Client());
  late final Dio _dio = widget.dio ?? (Dio()..interceptors.add(StackAuditDioInterceptor()));
  String _status = 'Run a scenario, then shake the device or tap "Open viewer".';

  Future<void> _run(String label, Future<String> Function() action) async {
    setState(() => _status = '$label…');
    try {
      final result = await action();
      if (mounted) setState(() => _status = '$label: $result');
    } catch (error) {
      if (mounted) setState(() => _status = '$label failed: $error');
    }
  }

  Future<String> _get() async {
    final response = await _client.get(Uri.parse('https://httpbin.org/get?page=1'));
    return 'HTTP ${response.statusCode}';
  }

  Future<String> _login() async {
    final response = await _client.post(
      Uri.parse('https://httpbin.org/post'),
      headers: {'Authorization': 'Bearer abcdefghijkl1234', 'Content-Type': 'application/json'},
      body: jsonEncode({'user': 'ana', 'password': 'hunter2'}),
    );
    return 'HTTP ${response.statusCode}, secrets masked in the viewer';
  }

  Future<String> _dioGet() async {
    final response = await _dio.get<Object?>('https://httpbin.org/json');
    return 'HTTP ${response.statusCode}';
  }

  Future<String> _notFound() async {
    final response = await _client.get(Uri.parse('https://httpbin.org/status/404'));
    return 'HTTP ${response.statusCode}';
  }

  Future<String> _analytics() async {
    await StackAudit.recordAnalytics('firebase', 'checkout_started', {'items': 2, 'value': 59.9});
    return 'event recorded';
  }

  Future<String> _flag() async {
    final enabled = await StackAudit.flagBool('new_checkout', defaultValue: false, remote: true);
    return 'new_checkout = $enabled';
  }

  Future<String> _storage() async {
    await StackAudit.recordStorage(
      store: 'shared_preferences',
      key: 'theme',
      operation: StorageOperation.write,
      newValue: 'dark',
    );
    return 'write recorded';
  }

  Future<String> _show() async {
    final shown = await StackAudit.show();
    return shown ? 'opened' : 'not available';
  }

  @override
  Widget build(BuildContext context) {
    final scenarios = <(String, IconData, Future<String> Function())>[
      ('GET request', Icons.download, _get),
      ('POST login with secrets', Icons.lock, _login),
      ('Dio request', Icons.http, _dioGet),
      ('Request that returns 404', Icons.error_outline, _notFound),
      ('Analytics event', Icons.insights, _analytics),
      ('Feature flag', Icons.flag, _flag),
      ('Storage write', Icons.save, _storage),
    ];
    return Scaffold(
      appBar: AppBar(title: const Text('StackAudit example')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(_status, key: const Key('status')),
          const SizedBox(height: 16),
          for (final (label, icon, action) in scenarios)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: OutlinedButton.icon(
                onPressed: () => _run(label, action),
                icon: Icon(icon),
                label: Text(label),
              ),
            ),
          const SizedBox(height: 8),
          FilledButton.icon(
            onPressed: () => _run('Open viewer', _show),
            icon: const Icon(Icons.manage_search),
            label: const Text('Open viewer'),
          ),
        ],
      ),
    );
  }
}
