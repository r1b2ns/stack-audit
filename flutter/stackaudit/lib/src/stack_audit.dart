import 'package:flutter/services.dart';

enum SecretsMode { mask, show, showAfterHandshake }

enum AccessMode { open, handshake, hidden }

enum StorageOperation { read, write, remove, clear }

class StackAuditOptions {
  const StackAuditOptions({
    this.secrets = SecretsMode.mask,
    this.extraHeaders = const [],
    this.extraFields = const [],
    this.allow = const [],
    this.access = AccessMode.open,
    this.publicKey,
    this.appId,
    this.sessionTtl = const Duration(minutes: 30),
    this.retention = const Duration(days: 7),
    this.maxBodySize = 1024 * 1024,
    this.maxStorage = 200 * 1024 * 1024,
    this.shakeToOpen = true,
    this.captureNativeNetwork = true,
  });

  final SecretsMode secrets;
  final List<String> extraHeaders;
  final List<String> extraFields;
  final List<String> allow;
  final AccessMode access;
  final String? publicKey;
  final String? appId;
  final Duration sessionTtl;
  final Duration retention;
  final int maxBodySize;
  final int maxStorage;
  final bool shakeToOpen;
  final bool captureNativeNetwork;

  Map<String, Object?> toMap() => {
        'secrets': secrets.name,
        'extraHeaders': extraHeaders,
        'extraFields': extraFields,
        'allow': allow,
        'access': access.name,
        'publicKey': publicKey,
        'appId': appId,
        'sessionTtlSeconds': sessionTtl.inSeconds,
        'retentionSeconds': retention.inSeconds,
        'maxBodySize': maxBodySize,
        'maxStorage': maxStorage,
        'shakeToOpen': shakeToOpen,
        'captureNativeNetwork': captureNativeNetwork,
      };
}

class HttpRecord {
  const HttpRecord({
    required this.method,
    required this.url,
    required this.startedAt,
    required this.duration,
    this.requestHeaders = const {},
    this.requestBody,
    this.status,
    this.responseHeaders = const {},
    this.responseBody,
    this.error,
    this.requestBodySize,
    this.responseBodySize,
    this.source = 'flutter',
  });

  final String method;
  final String url;
  final DateTime startedAt;
  final Duration duration;
  final Map<String, String> requestHeaders;
  final Uint8List? requestBody;
  final int? status;
  final Map<String, String> responseHeaders;
  final Uint8List? responseBody;
  final String? error;
  final int? requestBodySize;
  final int? responseBodySize;
  final String source;

  Map<String, Object?> toMap() => {
        'source': source,
        'method': method,
        'url': url,
        'requestHeaders': requestHeaders,
        'requestBody': requestBody,
        'status': status,
        'responseHeaders': responseHeaders,
        'responseBody': responseBody,
        'startedAt': startedAt.millisecondsSinceEpoch,
        'durationMs': duration.inMilliseconds,
        'error': error,
        'requestBodySize': requestBodySize,
        'responseBodySize': responseBodySize,
      };
}

class StackAudit {
  StackAudit._();

  static const MethodChannel channel = MethodChannel('io.github.r1b2ns.stackaudit');

  static StackAuditOptions? _options;

  static bool get isStarted => _options != null;

  static int get maxBodySize => _options?.maxBodySize ?? 0;

  static Future<void> start([StackAuditOptions options = const StackAuditOptions()]) async {
    if (options.access == AccessMode.handshake && options.publicKey == null) {
      throw ArgumentError('AccessMode.handshake requires a publicKey');
    }
    await channel.invokeMethod<void>('start', options.toMap());
    _options = options;
  }

  static Future<void> recordHttp(HttpRecord record) async {
    if (!isStarted) return;
    await channel.invokeMethod<void>('recordHttp', record.toMap());
  }

  static Future<void> recordStorage({
    required String store,
    required StorageOperation operation,
    String? key,
    String? oldValue,
    String? newValue,
    String? valueType,
    String source = 'flutter',
  }) async {
    if (!isStarted) return;
    await channel.invokeMethod<void>('recordStorage', {
      'source': source,
      'store': store,
      'key': key,
      'operation': operation.name,
      'oldValue': oldValue,
      'newValue': newValue,
      'valueType': valueType,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
    });
  }

  static Future<void> recordAnalytics(String provider, String name, [Map<String, Object?> params = const {}]) async {
    if (!isStarted) return;
    await channel.invokeMethod<void>('recordAnalytics', {'provider': provider, 'name': name, 'params': params});
  }

  static Future<void> setTrackingPlan(String? json) => channel.invokeMethod<void>('setTrackingPlan', {'json': json});

  static Future<bool> flagBool(String key, {required bool defaultValue, bool? remote, String provider = 'app'}) =>
      _flag<bool>(key, 'bool', defaultValue, remote, provider);

  static Future<String> flagString(String key, {required String defaultValue, String? remote, String provider = 'app'}) =>
      _flag<String>(key, 'string', defaultValue, remote, provider);

  static Future<double> flagNumber(String key, {required double defaultValue, num? remote, String provider = 'app'}) async {
    final value = await _flag<num>(key, 'number', defaultValue, remote, provider);
    return value.toDouble();
  }

  static Future<T> _flag<T>(String key, String kind, T defaultValue, Object? remote, String provider) async {
    if (!isStarted) return (remote as T?) ?? defaultValue;
    final value = await channel.invokeMethod<T>('flag', {
      'key': key,
      'kind': kind,
      'value': remote,
      'default': defaultValue,
      'provider': provider,
    });
    return value ?? defaultValue;
  }

  static Future<String?> handshake(String token) => channel.invokeMethod<String>('handshake', {'token': token});

  static Future<bool> handleLink(String url) async =>
      await channel.invokeMethod<bool>('handleLink', {'url': url}) ?? false;

  static Future<String?> deviceId() => channel.invokeMethod<String>('deviceId', const {});

  static Future<bool> show() async => await channel.invokeMethod<bool>('show', const {}) ?? false;

  static void resetForTesting() {
    _options = null;
  }
}
