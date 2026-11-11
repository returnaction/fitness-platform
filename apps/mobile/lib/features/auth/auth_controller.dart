import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';

import '../../core/network/identity_api_client.dart';
import '../../core/security/token_storage.dart';
import 'auth_repository.dart';

enum AuthStatus { guest, authenticated, unavailable }

final tokenStorageProvider = Provider<TokenStorage>(
  (ref) => TokenStorage(const FlutterSecureStorage()),
);

final identityApiClientProvider = Provider<IdentityApiClient>(
  (ref) => IdentityApiClient.create(),
);

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    ref.watch(identityApiClientProvider),
    ref.watch(tokenStorageProvider),
  ),
);

final uuidProvider = Provider<Uuid>((ref) => const Uuid());

final authControllerProvider =
    AsyncNotifierProvider<AuthController, AuthStatus>(AuthController.new);

class AuthController extends AsyncNotifier<AuthStatus> {
  @override
  Future<AuthStatus> build() async {
    return _restore();
  }

  Future<void> restore() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(_restore);
  }

  Future<void> login({required String email, required String password}) {
    return _authenticate(
      () => ref
          .read(authRepositoryProvider)
          .login(email: email, password: password),
    );
  }

  Future<void> register({
    required String email,
    required String password,
    required String idempotencyKey,
  }) {
    return _authenticate(
      () => ref
          .read(authRepositoryProvider)
          .register(
            email: email,
            password: password,
            idempotencyKey: idempotencyKey,
          ),
    );
  }

  Future<bool> logout() => _logout(ref.read(authRepositoryProvider).logout);

  Future<bool> logoutAll() =>
      _logout(ref.read(authRepositoryProvider).logoutAll);

  Future<void> _authenticate(Future<void> Function() action) async {
    state = const AsyncLoading();
    try {
      await action();
      state = const AsyncData(AuthStatus.authenticated);
    } catch (error, stackTrace) {
      state = AsyncError(error, stackTrace);
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  Future<bool> _logout(Future<bool> Function() action) async {
    state = const AsyncLoading();
    final remoteLogoutSucceeded = await action();
    state = const AsyncData(AuthStatus.guest);
    return remoteLogoutSucceeded;
  }

  Future<AuthStatus> _restore() async {
    return switch (await ref.read(authRepositoryProvider).restore()) {
      SessionRestoreResult.authenticated => AuthStatus.authenticated,
      SessionRestoreResult.guest => AuthStatus.guest,
      SessionRestoreResult.unavailable => AuthStatus.unavailable,
    };
  }
}
