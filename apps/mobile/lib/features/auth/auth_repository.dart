import '../../core/network/identity_api_client.dart';
import '../../core/network/identity_api_error.dart';
import '../../core/security/token_storage.dart';
import 'model/session_tokens.dart';
import 'session_operation_coordinator.dart';

enum SessionRestoreResult {
  authenticated,
  guest,
  temporarilyUnavailable,
  storageFailure,
}

class LogoutResult {
  const LogoutResult({
    required this.remoteSucceeded,
    required this.localTokensCleared,
  });

  final bool remoteSucceeded;
  final bool localTokensCleared;
}

class AuthRepository {
  AuthRepository(this._api, this._tokenStorage, this._coordinator);

  final AuthApiClient _api;
  final TokenStorage _tokenStorage;
  final SessionOperationCoordinator _coordinator;

  Future<void> register({
    required String email,
    required String password,
    required String idempotencyKey,
  }) => _coordinator.run(() async {
    final response = await _api.post(
      '/api/v1/auth/register',
      data: {'email': email, 'password': password},
      headers: {'Idempotency-Key': idempotencyKey},
    );
    final session = response['session'];
    if (session is! Map) {
      throw const FormatException('Identity response is missing a session.');
    }
    await _tokenStorage.write(
      SessionTokens.fromJson(Map<String, dynamic>.from(session)),
    );
  });

  Future<void> login({required String email, required String password}) =>
      _coordinator.run(() async {
        final response = await _api.post(
          '/api/v1/auth/login',
          data: {'email': email, 'password': password},
        );
        await _tokenStorage.write(SessionTokens.fromJson(response));
      });

  Future<SessionRestoreResult> restore() => _coordinator.run(_restore);

  Future<SessionRestoreResult> _restore() async {
    final SessionTokens? tokens;
    try {
      tokens = await _readTokens();
    } on TokenStorageException {
      return SessionRestoreResult.storageFailure;
    }
    if (tokens == null) {
      return SessionRestoreResult.guest;
    }
    // Remove the single-use token before sending it. A process death or a
    // failed write of the rotated pair must never make it reusable on restart.
    try {
      await _tokenStorage.clear();
    } on TokenStorageException {
      return SessionRestoreResult.storageFailure;
    }
    try {
      final response = await _api.post(
        '/api/v1/auth/refresh',
        data: {'refreshToken': tokens.refreshToken},
      );
      await _tokenStorage.write(SessionTokens.fromJson(response));
      return SessionRestoreResult.authenticated;
    } on IdentityApiException catch (error) {
      if (error.isIdentityUnavailable) {
        try {
          await _tokenStorage.write(tokens);
          return SessionRestoreResult.temporarilyUnavailable;
        } on TokenStorageException {
          return SessionRestoreResult.storageFailure;
        }
      }
      return _clearAfterUncertainRefresh();
    } on IdentityNetworkException {
      return _clearAfterUncertainRefresh();
    } on FormatException {
      return _clearAfterUncertainRefresh();
    } on TokenStorageException {
      return SessionRestoreResult.storageFailure;
    }
  }

  Future<LogoutResult> logout() => _coordinator.run(
    () => _logout('/api/v1/auth/logout', useRefreshToken: true),
  );

  Future<LogoutResult> logoutAll() => _coordinator.run(
    () => _logout('/api/v1/auth/logout-all', useRefreshToken: false),
  );

  Future<bool> discardLocalSession() =>
      _coordinator.run(_clearForExplicitLogout);

  Future<LogoutResult> _logout(
    String path, {
    required bool useRefreshToken,
  }) async {
    var remoteSucceeded = true;
    try {
      final tokens = await _readTokens();
      if (tokens != null) {
        await _api.postWithoutResponse(
          path,
          data: useRefreshToken ? {'refreshToken': tokens.refreshToken} : null,
          headers: useRefreshToken
              ? null
              : {'Authorization': 'Bearer ${tokens.accessToken}'},
        );
      }
    } catch (_) {
      remoteSucceeded = false;
    }
    return LogoutResult(
      remoteSucceeded: remoteSucceeded,
      localTokensCleared: await _clearForExplicitLogout(),
    );
  }

  Future<SessionTokens?> _readTokens() => _tokenStorage.read();

  Future<SessionRestoreResult> _clearAfterUncertainRefresh() async {
    try {
      await _tokenStorage.clear();
      return SessionRestoreResult.guest;
    } on TokenStorageException {
      return SessionRestoreResult.storageFailure;
    }
  }

  Future<bool> _clearForExplicitLogout() async {
    try {
      await _tokenStorage.clear();
      return true;
    } on TokenStorageException {
      return false;
    }
  }
}
