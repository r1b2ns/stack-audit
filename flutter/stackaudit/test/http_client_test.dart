import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stackaudit/stackaudit.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    StackAudit.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(StackAudit.channel, (_) async => null);
  });

  test('passes through without recording before start', () async {
    final records = <HttpRecord>[];
    final client = StackAuditHttpClient(MockClient((_) async => http.Response('ok', 200)), (r) async => records.add(r));
    final response = await client.get(Uri.parse('https://example.com'));
    expect(response.body, 'ok');
    expect(records, isEmpty);
  });

  test('records the exchange and keeps the body readable', () async {
    await StackAudit.start(const StackAuditOptions(maxBodySize: 1024));
    final records = <HttpRecord>[];
    final client = StackAuditHttpClient(
      MockClient((request) async => http.Response('{"id":1}', 201, headers: {'content-type': 'application/json'})),
      (r) async => records.add(r),
    );
    final response = await client.post(Uri.parse('https://api.example.com/items'), body: jsonEncode({'name': 'a'}));
    expect(response.statusCode, 201);
    expect(response.body, '{"id":1}');
    final record = records.single;
    expect(record.method, 'POST');
    expect(record.status, 201);
    expect(utf8.decode(record.requestBody!), '{"name":"a"}');
    expect(utf8.decode(record.responseBody!), '{"id":1}');
  });

  test('drops bodies above the limit but keeps the size', () async {
    await StackAudit.start(const StackAuditOptions(maxBodySize: 4));
    final records = <HttpRecord>[];
    final client = StackAuditHttpClient(MockClient((_) async => http.Response('0123456789', 200)), (r) async => records.add(r));
    await client.get(Uri.parse('https://example.com'));
    expect(records.single.responseBody, isNull);
    expect(records.single.responseBodySize, 10);
  });

  test('records failures and rethrows', () async {
    await StackAudit.start();
    final records = <HttpRecord>[];
    final client = StackAuditHttpClient(MockClient((_) async => throw http.ClientException('offline')), (r) async => records.add(r));
    await expectLater(client.get(Uri.parse('https://example.com')), throwsA(isA<http.ClientException>()));
    expect(records.single.error, contains('offline'));
  });
}
