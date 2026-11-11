import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('keeps tokens after confirmed Gateway identity_unavailable', () async {
    final storage = await _storageWithTokens();
    final repository = AuthRepository(
      _FakeAuthApiClient(
        postAction: (_, {data, headers}) => throw const IdentityApiException(
          statusCode: 503,
          code: 'identity_unavailable',
          message: 'Identity service is unavailable.',
        ),
      ),
      storage,
      SessionOperationCoordinator(),
    );

    expect(
      await repository.restore(),
      SessionRestoreResult.temporarilyUnavailable,
    );
    expect((await storage.read())?.refreshToken, 'refresh');
  });

  test('clears tokens after an uncertain refresh result', () async {
    final storage = await _storageWithTokens();
    final repository = AuthRepository(
      _FakeAuthApiClient(
        postAction: (_, {data, headers}) => throw const IdentityNetworkException(),
      ),
      storage,
      SessionOperationCoordinator(),
    );

    expect(await repository.restore(), SessionRestoreResult.guest);
    expect(await storage.read(), isNull);
  });

  test('clears local tokens when remote logout fails', () async {
    final storage = await _storageWithTokens();
    final repository = AuthRepository(
      _FakeAuthApiClient(
        postWithoutResponseAction: (_, {data, headers}) =>
            throw const IdentityNetworkException(),
      ),
      storage,
      SessionOperationCoordinator(),
    );

    final result = await repository.logoutAll();

    expect(result.remoteSucceeded, isFalse);
    expect(result.localTokensCleared, isTrue);
    expect(await storage.read(), isNull);
  });
}

Future<TokenStorage> _storageWithTokens() async {
  final storage = TokenStorage(_MemorySecureValueStore());
  await storage.write(
    const SessionTokens(accessToken: 'access', refreshToken: 'refresh'),
  );
  return storage;
}

class _FakeAuthApiClient implements AuthApiClient {
  _FakeAuthApiClient({this.postAction, this.postWithoutResponseAction});

  final Future<Map<String, dynamic>> Function(
    String path, {
    Object? data,
    Map<String, String>? headers,
  })?
  postAction;
  final Future<void> Function(
    String path, {
    Object? data,
    Map<String, String>? headers,
  })?
  postWithoutResponseAction;

  @override
  Future<Map<String, dynamic>> post(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) =>
      postAction?.call(path, data: data, headers: headers) ??
      Future<Map<String, dynamic>>.value({});

  @override
  Future<void> postWithoutResponse(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) =>
      postWithoutResponseAction?.call(path, data: data, headers: headers) ??
      Future<void>.value();
}

class _MemorySecureValueStore implements SecureValueStore {
  final _values = <String, String>{};

  @override
  Future<void> delete(String key) async {
    _values.remove(key);
  }

  @override
  Future<String?> read(String key) async => _values[key];

  @override
  Future<void> write(String key, String value) async {
    _values[key] = value;
  }
}
