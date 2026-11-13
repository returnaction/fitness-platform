import 'dart:async';

import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_controller.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const old = SessionTokens(accessToken: 'a', refreshToken: 'r');
  const rotated = {'accessToken': 'new-a', 'refreshToken': 'new-r'};
  for (final failed in [false, true]) {
    test(
      'concurrent refreshes never reuse a token after ${failed ? 'failure' : 'rotation'}',
      () async {
        final storage = TokenStorage(_Store());
        await storage.write(old);
        final api = _Api();
        final repo = AuthRepository(
          api,
          storage,
          SessionOperationCoordinator(),
        );
        final first = repo.restore();
        await api.started.future;
        final second = repo.restore();
        expect(api.refreshTokens, ['r']);
        if (failed) {
          api.response.completeError(const IdentityNetworkException());
        } else {
          api.response.complete(rotated);
        }
        await Future.wait([first, second]);
        expect(api.refreshTokens, failed ? ['r'] : ['r', 'new-r']);
        expect(
          (await storage.read())?.refreshToken,
          failed ? isNull : 'last-r',
        );
      },
    );
  }

  test(
    'logout queued behind refresh uses rotated token and leaves no session',
    () async {
      final storage = TokenStorage(_Store());
      await storage.write(old);
      final api = _Api();
      final repo = AuthRepository(api, storage, SessionOperationCoordinator());
      final refresh = repo.restore();
      await api.started.future;
      final logout = repo.logout();
      expect(api.logoutToken, isNull);
      api.response.complete(rotated);
      await api.logoutStarted.future;
      expect(api.logoutToken, 'new-r');
      api.logoutResponse.complete();
      await refresh;
      expect((await logout).localTokensCleared, isTrue);
      expect(await storage.read(), isNull);
    },
  );

  test(
    'controller keeps later logout loading while earlier login finishes',
    () async {
      final storage = TokenStorage(_Store());
      final api = _Api();
      final repo = AuthRepository(api, storage, SessionOperationCoordinator());
      final container = ProviderContainer(
        overrides: [authRepositoryProvider.overrideWithValue(repo)],
      );
      addTearDown(container.dispose);
      await container.read(authControllerProvider.future);
      final controller = container.read(authControllerProvider.notifier);
      final login = controller.login(
        email: 'test@example.com',
        password: 'password',
      );
      await api.started.future;
      final logout = controller.logout();
      api.response.complete(rotated);
      await login;
      await api.logoutStarted.future;
      final loadingWhileLogoutPending = container
          .read(authControllerProvider)
          .isLoading;
      api.logoutResponse.complete();
      await logout;
      expect(loadingWhileLogoutPending, isTrue);
      expect(
        container.read(authControllerProvider).requireValue,
        AuthStatus.guest,
      );
      expect(await storage.read(), isNull);
    },
  );
}

class _Api implements AuthApiClient {
  final started = Completer<void>();
  final response = Completer<Map<String, dynamic>>();
  final logoutStarted = Completer<void>();
  final logoutResponse = Completer<void>();
  final refreshTokens = <String>[];
  String? logoutToken;
  @override
  Future<Map<String, dynamic>> post(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {
    if (path.endsWith('/refresh')) {
      refreshTokens.add((data as Map)['refreshToken'] as String);
    }
    if (!started.isCompleted) {
      started.complete();
      return response.future;
    }
    return {'accessToken': 'last-a', 'refreshToken': 'last-r'};
  }

  @override
  Future<void> postWithoutResponse(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {
    logoutToken = (data as Map)['refreshToken'] as String;
    logoutStarted.complete();
    await logoutResponse.future;
  }
}

class _Store implements SecureValueStore {
  String? value;
  @override
  Future<void> delete(String key) async {
    value = null;
  }

  @override
  Future<String?> read(String key) async => value;
  @override
  Future<void> write(String key, String value) async {
    this.value = value;
  }
}
