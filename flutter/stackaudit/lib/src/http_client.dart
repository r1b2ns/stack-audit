import 'dart:async';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import 'stack_audit.dart';

class StackAuditHttpClient extends http.BaseClient {
  StackAuditHttpClient([http.Client? inner, Future<void> Function(HttpRecord record)? recorder])
      : _inner = inner ?? http.Client(),
        _recorder = recorder ?? StackAudit.recordHttp;

  final http.Client _inner;
  final Future<void> Function(HttpRecord record) _recorder;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    if (!StackAudit.isStarted) return _inner.send(request);
    final limit = StackAudit.maxBodySize;
    final startedAt = DateTime.now();
    final watch = Stopwatch()..start();
    final requestBody = request is http.Request ? request.bodyBytes : null;
    final requestHeaders = Map<String, String>.of(request.headers);
    try {
      final response = await _inner.send(request);
      final bytes = await response.stream.toBytes();
      watch.stop();
      unawaited(_recorder(HttpRecord(
        method: request.method,
        url: request.url.toString(),
        startedAt: startedAt,
        duration: watch.elapsed,
        requestHeaders: requestHeaders,
        requestBody: _limit(requestBody, limit),
        requestBodySize: requestBody?.length,
        status: response.statusCode,
        responseHeaders: response.headers,
        responseBody: _limit(bytes, limit),
        responseBodySize: bytes.length,
      )));
      return http.StreamedResponse(
        Stream<List<int>>.value(bytes),
        response.statusCode,
        contentLength: bytes.length,
        request: response.request,
        headers: response.headers,
        isRedirect: response.isRedirect,
        persistentConnection: response.persistentConnection,
        reasonPhrase: response.reasonPhrase,
      );
    } catch (error) {
      watch.stop();
      unawaited(_recorder(HttpRecord(
        method: request.method,
        url: request.url.toString(),
        startedAt: startedAt,
        duration: watch.elapsed,
        requestHeaders: requestHeaders,
        requestBody: _limit(requestBody, limit),
        requestBodySize: requestBody?.length,
        error: '${error.runtimeType}: $error',
      )));
      rethrow;
    }
  }

  static Uint8List? _limit(Uint8List? bytes, int limit) => bytes == null || bytes.length > limit ? null : bytes;

  @override
  void close() => _inner.close();
}
