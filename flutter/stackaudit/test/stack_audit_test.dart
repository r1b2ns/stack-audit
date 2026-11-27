import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stack_audit/stack_audit.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    StackAudit.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(StackAudit.channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'flag':
          return (call.arguments as Map)['value'] ?? (call.arguments as Map)['default'];
        case 'handshake':
          return 'expired';
        case 'show':
        case 'handleLink':
          return true;
        default:
          return null;
      }
    });
  });

  test('start sends the options to the native side', () async {
    await StackAudit.start(const StackAuditOptions(secrets: SecretsMode.show, maxBodySize: 10));
    expect(calls.single.method, 'start');
    final args = calls.single.arguments as Map;
    expect(args['secrets'], 'show');
    expect(args['access'], 'open');
    expect(args['maxBodySize'], 10);
    expect(StackAudit.maxBodySize, 10);
  });

  test('handshake access needs a public key', () {
    expect(() => StackAudit.start(const StackAuditOptions(access: AccessMode.handshake)), throwsArgumentError);
  });

  test('records are dropped before start', () async {
    await StackAudit.recordAnalytics('firebase', 'login');
    expect(calls, isEmpty);
  });

  test('analytics, storage and flags reach the channel', () async {
    await StackAudit.start();
    await StackAudit.recordAnalytics('firebase', 'login', {'method': 'email'});
    await StackAudit.recordStorage(store: 'prefs', key: 'token', operation: StorageOperation.write, newValue: 'x');
    expect(await StackAudit.flagBool('dark', defaultValue: false, remote: true), isTrue);
    expect(await StackAudit.flagNumber('limit', defaultValue: 3), 3.0);
    expect(calls.map((c) => c.method), ['start', 'recordAnalytics', 'recordStorage', 'flag', 'flag']);
    expect((calls[2].arguments as Map)['operation'], 'write');
  });

  test('handshake returns the rejection reason', () async {
    await StackAudit.start();
    expect(await StackAudit.handshake('a.b'), 'expired');
    expect(await StackAudit.show(), isTrue);
  });
}
