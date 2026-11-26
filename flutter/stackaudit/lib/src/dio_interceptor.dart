import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'stack_audit.dart';

class StackAuditDioInterceptor extends Interceptor {
  StackAuditDioInterceptor({Future<void> Function(HttpRecord record)? recorder, this.source = 'dio'})
      : _recorder = recorder ?? StackAudit.recordHttp;

  final Future<void> Function(HttpRecord record) _recorder;
  final String source;

  static const _startedAtKey = 'stackaudit.startedAt';

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    if (StackAudit.isStarted) options.extra[_startedAtKey] = DateTime.now();
    handler.next(options);
  }

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    _record(response.requestOptions, response, null);
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    _record(err.requestOptions, err.response, err);
    handler.next(err);
  }

  void _record(RequestOptions options, Response<dynamic>? response, DioException? error) {
    final startedAt = options.extra[_startedAtKey];
    if (startedAt is! DateTime || !StackAudit.isStarted) return;
    final limit = StackAudit.maxBodySize;
    final requestBody = _encode(options.data);
    final responseBody = _encode(response?.data);
    unawaited(_recorder(HttpRecord(
      method: options.method.toUpperCase(),
      url: options.uri.toString(),
      startedAt: startedAt,
      duration: DateTime.now().difference(startedAt),
      requestHeaders: _requestHeaders(options),
      requestBody: _limit(requestBody, limit),
      requestBodySize: requestBody?.length,
      status: response?.statusCode,
      responseHeaders: response == null ? const {} : _responseHeaders(response.headers),
      responseBody: _limit(responseBody, limit),
      responseBodySize: responseBody?.length,
      error: error == null ? null : _describe(error),
      source: source,
    )));
  }

  static Map<String, String> _requestHeaders(RequestOptions options) {
    final headers = <String, String>{
      for (final entry in options.headers.entries)
        if (entry.value != null) entry.key: entry.value is List ? (entry.value as List).join(', ') : '${entry.value}',
    };
    final contentType = options.contentType;
    if (contentType != null && !headers.keys.any((key) => key.toLowerCase() == Headers.contentTypeHeader)) {
      headers[Headers.contentTypeHeader] = contentType;
    }
    return headers;
  }

  static Map<String, String> _responseHeaders(Headers headers) =>
      {for (final entry in headers.map.entries) entry.key: entry.value.join(', ')};

  static Uint8List? _encode(Object? data) {
    if (data == null) return null;
    if (data is Uint8List) return data;
    if (data is List<int>) return Uint8List.fromList(data);
    if (data is String) return Uint8List.fromList(utf8.encode(data));
    if (data is FormData) return Uint8List.fromList(utf8.encode(_describeForm(data)));
    if (data is Map || data is List || data is num || data is bool) {
      try {
        return Uint8List.fromList(utf8.encode(jsonEncode(data)));
      } on Object {
        return null;
      }
    }
    return null;
  }

  static String _describeForm(FormData form) => [
        for (final field in form.fields) '${Uri.encodeQueryComponent(field.key)}=${Uri.encodeQueryComponent(field.value)}',
        for (final file in form.files) '${Uri.encodeQueryComponent(file.key)}=<file ${file.value.filename ?? ''}>',
      ].join('&');

  static String _describe(DioException error) {
    final message = error.message ?? error.error?.toString() ?? '';
    return message.isEmpty ? 'DioException [${error.type.name}]' : 'DioException [${error.type.name}]: $message';
  }

  static Uint8List? _limit(Uint8List? bytes, int limit) => bytes == null || bytes.length > limit ? null : bytes;
}
