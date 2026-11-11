import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../features/auth/model/session_tokens.dart';

class TokenStorage {
  TokenStorage(this._storage);

  static const _accessTokenKey = 'access_token';
  static const _refreshTokenKey = 'refresh_token';

  final FlutterSecureStorage _storage;

  Future<SessionTokens?> read() async {
    final values = await _storage.readAll();
    final accessToken = values[_accessTokenKey];
    final refreshToken = values[_refreshTokenKey];
    if (accessToken == null || refreshToken == null) {
      return null;
    }
    return SessionTokens(accessToken: accessToken, refreshToken: refreshToken);
  }

  Future<void> write(SessionTokens tokens) {
    return _storage
        .write(key: _accessTokenKey, value: tokens.accessToken)
        .then(
          (_) =>
              _storage.write(key: _refreshTokenKey, value: tokens.refreshToken),
        );
  }

  Future<void> clear() => _storage.deleteAll();
}
