import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';

import '../../core/network/identity_api_client.dart';
import '../../core/security/token_storage.dart';
import 'auth_repository.dart';
import 'session_operation_coordinator.dart';

enum AuthStatus {
  guest,
  authenticated,
  temporarilyUnavailable,
  storageFailure,
}

final tokenStorageProvider = Provider<TokenStorage>(
  (ref) => TokenStorage(
    FlutterSecureValueStore(const FlutterSecureStorage()),
  ),
);

final identityApiClientProvider = Provider<IdentityApiClient>(
  (ref) => IdentityApiClient.create(),
);

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    ref.watch(identityApiClientProvider),
    ref.watch(tokenStorageProvider),
    ref.watch(sessionOperationCoordinatorProvider),
  ),
);

final sessionOperationCoordinatorProvider =
    Provider<SessionOperationCoordinator>((ref) => SessionOperationCoordinator());

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

  Future<LogoutResult> logout() =>
      _logout(ref.read(authRepositoryProvider).logout);

  Future<LogoutResult> logoutAll() =>
      _logout(ref.read(authRepositoryProvider).logoutAll);

  Future<void> discardLocalSession() async {
    state = const AsyncLoading();
    final tokensCleared =
        await ref.read(authRepositoryProvider).discardLocalSession();
    state = AsyncData(
      tokensCleared ? AuthStatus.guest : AuthStatus.storageFailure,
    );
  }

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

  Future<LogoutResult> _logout(Future<LogoutResult> Function() action) async {
    state = const AsyncLoading();
    final result = await action();
    state = AsyncData(
      result.localTokensCleared ? AuthStatus.guest : AuthStatus.storageFailure,
    );
    return result;
  }

  Future<AuthStatus> _restore() async {
    return switch (await ref.read(authRepositoryProvider).restore()) {
      SessionRestoreResult.authenticated => AuthStatus.authenticated,
      SessionRestoreResult.guest => AuthStatus.guest,
      SessionRestoreResult.temporarilyUnavailable =>
        AuthStatus.temporarilyUnavailable,
      SessionRestoreResult.storageFailure => AuthStatus.storageFailure,
    };
  }
}
