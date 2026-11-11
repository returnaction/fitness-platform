import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../features/auth/model/session_tokens.dart';

abstract interface class SecureValueStore {
  Future<void> delete(String key);

  Future<String?> read(String key);

  Future<void> write(String key, String value);
}

class FlutterSecureValueStore implements SecureValueStore {
  FlutterSecureValueStore(this._storage);

  final FlutterSecureStorage _storage;

  @override
  Future<void> delete(String key) => _storage.delete(key: key);

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);
}

class TokenStorageException implements Exception {
  const TokenStorageException();
}

class TokenStorage {
  TokenStorage(this._storage);

  static const _sessionKey = 'auth_session_v1';

  final SecureValueStore _storage;

  Future<SessionTokens?> read() async {
    try {
      final encodedSession = await _storage.read(_sessionKey);
      if (encodedSession == null) {
        return null;
      }
      final decodedSession = jsonDecode(encodedSession);
      if (decodedSession is! Map) {
        throw const FormatException('Stored session must be an object.');
      }
      return SessionTokens.fromJson(Map<String, dynamic>.from(decodedSession));
    } catch (_) {
      throw const TokenStorageException();
    }
  }

  Future<void> write(SessionTokens tokens) async {
    try {
      await _storage.write(_sessionKey, jsonEncode(tokens.toJson()));
    } catch (_) {
      await _deleteAfterFailedWrite();
      throw const TokenStorageException();
    }
  }

  Future<void> clear() async {
    try {
      await _storage.delete(_sessionKey);
    } catch (_) {
      throw const TokenStorageException();
    }
  }

  Future<void> _deleteAfterFailedWrite() async {
    try {
      await _storage.delete(_sessionKey);
    } catch (_) {
      // The caller receives the original storage failure without secret details.
    }
  }
}
