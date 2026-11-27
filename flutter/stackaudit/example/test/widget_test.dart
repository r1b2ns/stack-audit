import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';
import 'package:stack_audit/stack_audit.dart';
import 'package:stack_audit_example/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];

  setUp(() async {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(StackAudit.channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'flag':
          return true;
        case 'show':
          return true;
      }
      return null;
    });
    StackAudit.resetForTesting();
    await StackAudit.start();
  });

  Future<void> pumpApp(WidgetTester tester, http.Client client) async {
    await tester.pumpWidget(ExampleApp(client: StackAuditHttpClient(client)));
  }

  Future<void> tapScenario(WidgetTester tester, String label) async {
    await tester.scrollUntilVisible(find.text(label), 100);
    await tester.tap(find.text(label));
    await tester.pumpAndSettle();
  }

  String status(WidgetTester tester) => tester.widget<Text>(find.byKey(const Key('status'))).data!;

  testWidgets('shows every scenario and the viewer button', (tester) async {
    await pumpApp(tester, MockClient((_) async => http.Response('{}', 200)));
    for (final label in [
      'GET request',
      'POST login with secrets',
      'Dio request',
      'Request that returns 404',
      'Analytics event',
      'Feature flag',
      'Storage write',
      'Open viewer',
    ]) {
      await tester.scrollUntilVisible(find.text(label), 100);
      expect(find.text(label), findsOneWidget);
    }
  });

  testWidgets('records the login request with its headers and body', (tester) async {
    final client = MockClient((request) async {
      expect(request.headers['Authorization'], 'Bearer abcdefghijkl1234');
      expect(jsonDecode(request.body), {'user': 'ana', 'password': 'hunter2'});
      return http.Response('{"ok":true}', 200);
    });
    await pumpApp(tester, client);
    await tapScenario(tester, 'POST login with secrets');
    final record = calls.singleWhere((call) => call.method == 'recordHttp').arguments as Map;
    expect(record['method'], 'POST');
    expect(record['url'], 'https://httpbin.org/post');
    expect(record['status'], 200);
    expect(status(tester), 'POST login with secrets: HTTP 200, secrets masked in the viewer');
  });

  testWidgets('records analytics, flag and storage through the channel', (tester) async {
    await pumpApp(tester, MockClient((_) async => http.Response('{}', 200)));
    await tapScenario(tester, 'Analytics event');
    await tapScenario(tester, 'Feature flag');
    expect(status(tester), 'Feature flag: new_checkout = true');
    await tapScenario(tester, 'Storage write');
    final methods = calls.map((call) => call.method).toList();
    expect(methods, containsAllInOrder(['recordAnalytics', 'flag', 'recordStorage']));
    final event = calls.firstWhere((call) => call.method == 'recordAnalytics').arguments as Map;
    expect(event['name'], 'checkout_started');
  });

  testWidgets('opens the viewer', (tester) async {
    await pumpApp(tester, MockClient((_) async => http.Response('{}', 200)));
    await tapScenario(tester, 'Open viewer');
    expect(calls.last.method, 'show');
    expect(status(tester), 'Open viewer: opened');
  });

  testWidgets('shows the error when a request fails', (tester) async {
    await pumpApp(tester, MockClient((_) async => throw http.ClientException('offline')));
    await tapScenario(tester, 'GET request');
    expect(status(tester), startsWith('GET request failed:'));
  });
}
