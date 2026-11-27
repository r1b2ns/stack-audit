import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stack_audit/dio.dart';
import 'package:stack_audit/stack_audit.dart';

class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.respond);

  final Future<ResponseBody> Function(RequestOptions options) respond;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) {
    requests.add(options);
    return respond(options);
  }

  @override
  void close({bool force = false}) {}
}

Dio _dio(_FakeAdapter adapter, List<HttpRecord> records) {
  final dio = Dio()..httpClientAdapter = adapter;
  dio.interceptors.add(StackAuditDioInterceptor(recorder: (record) async => records.add(record)));
  return dio;
}

ResponseBody _json(String body, int status) => ResponseBody.fromString(
      body,
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );

Future<void> _settle() => Future<void>.delayed(Duration.zero);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    StackAudit.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(StackAudit.channel, (_) async => null);
  });

  test('passes through without recording before start', () async {
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((_) async => _json('{"ok":true}', 200)), records);
    final response = await dio.get<Map<String, dynamic>>('https://example.com/a');
    await _settle();
    expect(response.data, {'ok': true});
    expect(records, isEmpty);
  });

  test('records a JSON exchange and leaves the response untouched', () async {
    await StackAudit.start(const StackAuditOptions(maxBodySize: 1024));
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((_) async => _json('{"id":1}', 201)), records);
    final response = await dio.post<Map<String, dynamic>>(
      'https://api.example.com/items?page=2',
      data: {'name': 'a'},
      options: Options(headers: {'Authorization': 'Bearer abc'}),
    );
    await _settle();
    expect(response.statusCode, 201);
    expect(response.data, {'id': 1});
    final record = records.single;
    expect(record.method, 'POST');
    expect(record.url, 'https://api.example.com/items?page=2');
    expect(record.status, 201);
    expect(record.source, 'dio');
    expect(record.requestHeaders['Authorization'], 'Bearer abc');
    expect(record.requestHeaders[Headers.contentTypeHeader], Headers.jsonContentType);
    expect(utf8.decode(record.requestBody!), '{"name":"a"}');
    expect(utf8.decode(record.responseBody!), '{"id":1}');
    expect(record.responseHeaders[Headers.contentTypeHeader], Headers.jsonContentType);
    expect(record.error, isNull);
  });

  test('records plain text and byte bodies', () async {
    await StackAudit.start();
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((_) async => ResponseBody.fromBytes([1, 2, 3], 200)), records);
    await dio.put<List<int>>(
      'https://example.com/raw',
      data: 'hello',
      options: Options(responseType: ResponseType.bytes, contentType: 'text/plain'),
    );
    await _settle();
    expect(utf8.decode(records.single.requestBody!), 'hello');
    expect(records.single.responseBody, [1, 2, 3]);
  });

  test('describes form data without file contents', () async {
    await StackAudit.start();
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((_) async => _json('{}', 200)), records);
    final form = FormData.fromMap({
      'user': 'ana',
      'avatar': MultipartFile.fromBytes([1, 2, 3], filename: 'a.png'),
    });
    await dio.post<dynamic>('https://example.com/upload', data: form);
    await _settle();
    expect(utf8.decode(records.single.requestBody!), 'user=ana&avatar=<file a.png>');
  });

  test('drops bodies above the limit but keeps the size', () async {
    await StackAudit.start(const StackAuditOptions(maxBodySize: 4));
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((_) async => ResponseBody.fromString('0123456789', 200)), records);
    await dio.get<String>('https://example.com', options: Options(responseType: ResponseType.plain));
    await _settle();
    expect(records.single.responseBody, isNull);
    expect(records.single.responseBodySize, 10);
  });

  test('records error responses with their status and rethrows', () async {
    await StackAudit.start();
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((_) async => _json('{"error":"missing"}', 404)), records);
    await expectLater(dio.get<dynamic>('https://example.com/missing'), throwsA(isA<DioException>()));
    await _settle();
    final record = records.single;
    expect(record.status, 404);
    expect(utf8.decode(record.responseBody!), '{"error":"missing"}');
    expect(record.error, contains('badResponse'));
  });

  test('records connection failures without a status', () async {
    await StackAudit.start();
    final records = <HttpRecord>[];
    final dio = _dio(_FakeAdapter((options) async => throw DioException.connectionError(requestOptions: options, reason: 'offline')), records);
    await expectLater(dio.get<dynamic>('https://example.com'), throwsA(isA<DioException>()));
    await _settle();
    expect(records.single.status, isNull);
    expect(records.single.error, contains('offline'));
  });

  test('uses a custom source name', () async {
    await StackAudit.start();
    final records = <HttpRecord>[];
    final dio = Dio()..httpClientAdapter = _FakeAdapter((_) async => _json('{}', 200));
    dio.interceptors.add(StackAuditDioInterceptor(recorder: (record) async => records.add(record), source: 'api'));
    await dio.get<dynamic>('https://example.com');
    await _settle();
    expect(records.single.source, 'api');
  });
}
