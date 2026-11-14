import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';

import '../../core/network/identity_api_client.dart';
import '../../core/security/token_storage.dart';
import '../profile/profile_store.dart';
import 'auth_repository.dart';
import 'session_operation_coordinator.dart';

enum AuthStatus { guest, authenticated, temporarilyUnavailable, storageFailure }

final tokenStorageProvider = Provider<TokenStorage>(
  (ref) => TokenStorage(FlutterSecureValueStore(const FlutterSecureStorage())),
);

final identityApiClientProvider = Provider<IdentityApiClient>(
  (ref) => IdentityApiClient.create(),
);

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    ref.watch(identityApiClientProvider),
    ref.watch(tokenStorageProvider),
    ref.watch(sessionOperationCoordinatorProvider),
    invalidateSessionData: ref.watch(profileStoreProvider).invalidate,
    clearSessionData: ref.watch(profileStoreProvider).clear,
    onRequestSessionFailure: (result, generation) {
      if (ref.mounted) {
        ref.read(authControllerProvider.notifier).handleRequestSessionFailure(result, generation);
      }
    },
  ),
);

final sessionOperationCoordinatorProvider =
    Provider<SessionOperationCoordinator>(
      (ref) => SessionOperationCoordinator(),
    );

final uuidProvider = Provider<Uuid>((ref) => const Uuid());

final authControllerProvider =
    AsyncNotifierProvider<AuthController, AuthStatus>(AuthController.new);

class AuthController extends AsyncNotifier<AuthStatus> {
  int _operationId = 0;

  @override
  Future<AuthStatus> build() async {
    return _restore();
  }

  void handleRequestSessionFailure(SessionRestoreResult result, int generation) {
    if (!ref.mounted ||
        ref.read(authRepositoryProvider).sessionGeneration != generation ||
        state.asData?.value != AuthStatus.authenticated) {
      return;
    }
    if (result == SessionRestoreResult.guest || result == SessionRestoreResult.storageFailure) {
      _operationId++;
      state = AsyncData(result == SessionRestoreResult.guest
          ? AuthStatus.guest : AuthStatus.storageFailure);
    }
    // A temporary refresh outage leaves the current form and session intact.
  }

  Future<void> restore() async {
    final operation = _beginOperation();
    final result = await AsyncValue.guard(_restore);
    _publish(operation, result);
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
    final operation = _beginOperation();
    final tokensCleared = await ref
        .read(authRepositoryProvider)
        .discardLocalSession();
    _publish(
      operation,
      AsyncData(tokensCleared ? AuthStatus.guest : AuthStatus.storageFailure),
    );
  }

  Future<void> _authenticate(Future<void> Function() action) async {
    final operation = _beginOperation();
    try {
      await action();
      _publish(operation, const AsyncData(AuthStatus.authenticated));
    } catch (error, stackTrace) {
      _publish(operation, const AsyncData(AuthStatus.guest));
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  Future<LogoutResult> _logout(Future<LogoutResult> Function() action) async {
    final operation = _beginOperation();
    final result = await action();
    _publish(
      operation,
      AsyncData(
        result.localTokensCleared
            ? AuthStatus.guest
            : AuthStatus.storageFailure,
      ),
    );
    return result;
  }

  int _beginOperation() {
    final operation = ++_operationId;
    state = const AsyncLoading();
    return operation;
  }

  void _publish(int operation, AsyncValue<AuthStatus> result) {
    if (ref.mounted && operation == _operationId) {
      state = result;
    }
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
