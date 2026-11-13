import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../core/security/token_storage.dart';

/// Keeps encryption in flutter_secure_storage and waits for Android disk writes.
class DurableProfileStorage implements SecureValueStore {
  DurableProfileStorage(this._storage, {Future<void> Function()? flush})
      : _flush = flush ?? _flushAndroid;

  static const channel = MethodChannel('fitness/profile_storage');
  final SecureValueStore _storage;
  final Future<void> Function() _flush;

  static Future<void> _flushAndroid() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    if (await channel.invokeMethod<bool>('flush') != true) {
      throw PlatformException(code: 'profile_storage_flush_failed');
    }
  }

  @override
  Future<String?> read(String key) => _storage.read(key);

  @override
  Future<void> write(String key, String value) async {
    await _storage.write(key, value);
    await _flush();
  }

  @override
  Future<void> delete(String key) async {
    await _storage.delete(key);
    await _flush();
  }
}
