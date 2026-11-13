import 'dart:convert';

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
  AuthRepository(this._api, this._tokenStorage, this._coordinator, {
    this.invalidateSessionData,
    this.clearSessionData,
    this.onRequestSessionFailure,
  });

  final void Function()? invalidateSessionData;
  final Future<void> Function()? clearSessionData;
  final void Function(SessionRestoreResult result, int generation)? onRequestSessionFailure;

  final AuthApiClient _api;
  final TokenStorage _tokenStorage;
  final SessionOperationCoordinator _coordinator;

  int _sessionGeneration = 0;
  int get sessionGeneration => _sessionGeneration;
  SessionRestoreResult? _requestFailure;

  Future<void> register({
    required String email,
    required String password,
    required String idempotencyKey,
  }) => _changeSession(() async {
    await clearSessionData?.call();
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
      _changeSession(() async {
        await clearSessionData?.call();
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
      return _clearAfterUncertainRefresh();
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
      _requestFailure = null;
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

  Future<LogoutResult> logout() => _changeSession(
    () => _logout('/api/v1/auth/logout', useRefreshToken: true),
  );

  Future<LogoutResult> logoutAll() => _changeSession(
    () => _logout('/api/v1/auth/logout-all', useRefreshToken: false),
  );

  Future<bool> discardLocalSession() =>
      _changeSession(_clearForExplicitLogout);

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

  Future<T> _changeSession<T>(Future<T> Function() action) {
    _sessionGeneration++;
    _requestFailure = null;
    invalidateSessionData?.call();
    return _coordinator.run(action);
  }

  /// Resource requests do not hold the session queue during network I/O.
  /// Only an explicit 401 permits one retry after coordinated token refresh.
  Future<T> authenticatedRequest<T>(
    Future<T> Function(String accessToken) request,
  ) {
    final generation = _sessionGeneration;
    return _runForSession(generation, () => _authenticatedRequest(generation, request));
  }

  Future<T> _authenticatedRequest<T>(
    int generation, Future<T> Function(String accessToken) request,
  ) async {
    var tokens = await _coordinator.run(() => _requestTokens(generation));
    for (var attempt = 0; attempt < 2; attempt++) {
      _checkGeneration(generation);
      try {
        final result = await request(tokens.accessToken);
        _checkGeneration(generation);
        return result;
      } on IdentityApiException catch (error) {
        _checkGeneration(generation);
        if (error.statusCode != 401 || error.code != 'invalid_access_token') {
          rethrow;
        }
        tokens = await _coordinator.run(() async {
          final current = await _requestTokens(generation);
          if (attempt == 1) {
            final result = await _clearAfterUncertainRefresh();
            throw SessionRequestException(result);
          }
          // Another request may already have rotated this access token.
          if (current.accessToken == tokens.accessToken) {
            final result = await _restore();
            _checkGeneration(generation);
            if (result != SessionRestoreResult.authenticated) {
              throw SessionRequestException(result);
            }
          }
          return _requestTokens(generation);
        });
      }
    }
    throw const SessionRequestException(SessionRestoreResult.guest);
  }

  Future<SessionTokens> _requestTokens(int generation) async {
    _checkGeneration(generation);
    final tokens = await _readTokens();
    _checkGeneration(generation);
    if (tokens == null) {
      throw SessionRequestException(await _clearAfterUncertainRefresh());
    }
    return tokens;
  }

  void _checkGeneration(int generation) {
    if (generation != _sessionGeneration) {
      throw const SessionChangedException();
    }
  }

  Future<String> profileAccountId() {
    final generation = _sessionGeneration;
    return _runForSession(generation, () => _coordinator.run(() async {
      final tokens = await _requestTokens(generation);
      // Used only to associate local data, never to authorize server access.
      try {
        final parts = tokens.accessToken.split('.');
        if (parts.length != 3) throw const FormatException();
        final payload = jsonDecode(utf8.decode(base64Url.decode(
          base64Url.normalize(parts[1]),
        )));
        final subject = payload is Map ? payload['sub'] : null;
        if (subject is! String || subject.isEmpty) throw const FormatException();
        return subject;
      } catch (_) {
        throw const FormatException('Session has no profile account identifier.');
      }
    }));
  }

  Future<T> _runForSession<T>(int generation, Future<T> Function() action) async {
    try {
      final result = await action();
      _checkGeneration(generation);
      if (_requestFailure != null) throw SessionRequestException(_requestFailure!);
      return result;
    } catch (error) {
      _checkGeneration(generation);
      final failure = error is TokenStorageException
          ? const SessionRequestException(SessionRestoreResult.storageFailure)
          : error;
      if (failure is SessionRequestException) {
        if (failure.result == SessionRestoreResult.storageFailure) {
          _requestFailure = failure.result;
          invalidateSessionData?.call();
        }
        onRequestSessionFailure?.call(failure.result, generation);
      }
      throw failure;
    }
  }

  Future<SessionTokens?> _readTokens() => _tokenStorage.read();

  Future<SessionRestoreResult> _clearAfterUncertainRefresh() async {
    _requestFailure = SessionRestoreResult.guest;
    invalidateSessionData?.call();
    return await _clearForExplicitLogout()
        ? SessionRestoreResult.guest
        : SessionRestoreResult.storageFailure;
  }

  Future<bool> _clearForExplicitLogout() async {
    var cleared = true;
    try {
      await _tokenStorage.clear();
    } catch (_) {
      cleared = false;
    }
    try {
      await clearSessionData?.call();
    } catch (_) {
      cleared = false;
    }
    return cleared;
  }
}

class SessionRequestException implements Exception {
  const SessionRequestException(this.result);

  final SessionRestoreResult result;
}

class SessionChangedException implements Exception {
  const SessionChangedException();
}
