import '../../core/network/identity_api_client.dart';
import '../../core/network/identity_api_error.dart';
import '../../core/security/token_storage.dart';
import 'model/session_tokens.dart';

enum SessionRestoreResult { authenticated, guest, unavailable }

class AuthRepository {
  AuthRepository(this._api, this._tokenStorage);

  final IdentityApiClient _api;
  final TokenStorage _tokenStorage;

  Future<void> register({
    required String email,
    required String password,
    required String idempotencyKey,
  }) async {
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
  }

  Future<void> login({required String email, required String password}) async {
    final response = await _api.post(
      '/api/v1/auth/login',
      data: {'email': email, 'password': password},
    );
    await _tokenStorage.write(SessionTokens.fromJson(response));
  }

  Future<SessionRestoreResult> restore() async {
    final tokens = await _tokenStorage.read();
    if (tokens == null) {
      return SessionRestoreResult.guest;
    }
    try {
      final response = await _api.post(
        '/api/v1/auth/refresh',
        data: {'refresh_token': tokens.refreshToken},
      );
      await _tokenStorage.write(SessionTokens.fromJson(response));
      return SessionRestoreResult.authenticated;
    } on IdentityNetworkException {
      return SessionRestoreResult.unavailable;
    } on IdentityApiException {
      await _tokenStorage.clear();
      return SessionRestoreResult.guest;
    }
  }

  Future<bool> logout() async {
    final tokens = await _tokenStorage.read();
    try {
      if (tokens != null) {
        await _api.postWithoutResponse(
          '/api/v1/auth/logout',
          data: {'refresh_token': tokens.refreshToken},
        );
      }
      return true;
    } on IdentityApiException {
      return false;
    } on IdentityNetworkException {
      return false;
    } finally {
      await _tokenStorage.clear();
    }
  }

  Future<bool> logoutAll() async {
    final tokens = await _tokenStorage.read();
    try {
      if (tokens != null) {
        await _api.postWithoutResponse(
          '/api/v1/auth/logout-all',
          headers: {'Authorization': 'Bearer ${tokens.accessToken}'},
        );
      }
      return true;
    } on IdentityApiException {
      return false;
    } on IdentityNetworkException {
      return false;
    } finally {
      await _tokenStorage.clear();
    }
  }
}
