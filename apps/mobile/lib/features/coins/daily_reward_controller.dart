import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';

import '../../core/network/identity_api_error.dart';
import '../../core/security/token_storage.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_repository.dart';
import 'daily_reward_key_store.dart';
import 'daily_reward_repository.dart';
import 'model/daily_reward_claim.dart';
import 'model/daily_reward_status.dart';

final dailyRewardRepositoryProvider = Provider<DailyRewardRepository>(
  (ref) => HttpDailyRewardRepository(
    ref.watch(identityApiClientProvider),
    ref.watch(authRepositoryProvider),
  ),
);

final dailyRewardKeyStoreProvider = Provider<DailyRewardKeyStore>(
  (ref) => DailyRewardKeyStore(
    FlutterSecureValueStore(FlutterSecureStorage()),
  ),
);

final dailyRewardControllerProvider =
    Provider.autoDispose<DailyRewardController>((ref) {
  final controller = DailyRewardController(
    ref.watch(dailyRewardRepositoryProvider),
    ref.watch(dailyRewardKeyStoreProvider),
    ref.watch(authRepositoryProvider).profileAccountId,
    ref.watch(uuidProvider),
  );
  ref.listen(authControllerProvider, (previous, next) {
    if (previous?.asData?.value == AuthStatus.authenticated &&
        next.asData?.value != AuthStatus.authenticated) {
      controller.reset();
    }
  });
  ref.onDispose(controller.dispose);
  return controller;
});

class DailyRewardController extends ChangeNotifier {
  DailyRewardController(
    this._repository,
    this._keyStore,
    this._accountId,
    this._uuid,
  );

  final DailyRewardRepository _repository;
  final DailyRewardKeyStore _keyStore;
  final Future<String> Function() _accountId;
  final Uuid _uuid;

  DailyRewardStatus? status;
  bool loading = false;
  bool claiming = false;
  String? error;
  Duration remaining = Duration.zero;

  Timer? _timer;
  Duration? _remainingAtFetch;
  Stopwatch? _stopwatch;
  bool _refreshAtZero = false;
  bool _disposed = false;
  int _generation = 0;
  final Set<String> _consumedKeys = {};

  Future<void> open() async {
    if (status != null) return;
    await refresh();
  }

  Future<void> refresh() async {
    if (loading || claiming || _disposed) return;
    final generation = _generation;
    loading = true;
    error = null;
    _refreshAtZero = false;
    _notify();
    try {
      final fetched = await _repository.getStatus();
      if (!_active(generation)) return;
      _applyStatus(fetched);
    } catch (failure) {
      if (_active(generation)) error = _messageFor(failure);
    } finally {
      if (_active(generation)) {
        loading = false;
        _notify();
      }
    }
  }

  Future<DailyRewardClaim?> claim() async {
    final current = status;
    if (current == null || !current.available || claiming || loading || _disposed) {
      return null;
    }

    final generation = _generation;
    claiming = true;
    error = null;
    _notify();
    String? account;
    String? key;
    try {
      account = await _accountId();
      if (!_active(generation)) return null;
      final storedKey = await _keyStore.read(account);
      final attemptKey = storedKey == null || _consumedKeys.contains(storedKey)
          ? _uuid.v4()
          : storedKey;
      if (storedKey == null || _consumedKeys.contains(storedKey)) {
        await _keyStore.write(account, attemptKey);
      }
      if (!_active(generation)) return null;
      key = attemptKey;

      final claim = await _repository.claim(attemptKey);
      if (!_active(generation)) return null;
      _consumedKeys.add(attemptKey);
      try {
        await _keyStore.clear(account);
      } on DailyRewardKeyStorageException {
        // The operation is already committed; the consumed key is not reused.
      }
      if (!_active(generation)) return null;
      _applyClaim(claim);
      return claim;
    } on DailyRewardKeyStorageException {
      if (_active(generation)) {
        error = 'Cannot store the operation key securely. Try again.';
      }
      return null;
    } on IdentityApiException catch (failure) {
      if (failure.statusCode == 400 && key != null) {
        _consumedKeys.add(key);
        if (account != null) await _clearKey(account);
      }
      if (_active(generation)) error = _messageFor(failure);
      return null;
    } on IdentityNetworkException {
      if (_active(generation)) error = 'Cannot reach the server. Try again.';
      return null;
    } on SessionRequestException {
      if (_active(generation)) {
        error = 'Your session has changed. Sign in again.';
      }
      return null;
    } catch (_) {
      if (_active(generation)) error = 'Could not claim the daily bonus.';
      return null;
    } finally {
      if (_active(generation)) {
        claiming = false;
        _notify();
      }
    }
  }

  void reset() {
    _generation++;
    _timer?.cancel();
    _timer = null;
    _remainingAtFetch = null;
    _stopwatch = null;
    _refreshAtZero = false;
    _consumedKeys.clear();
    status = null;
    loading = false;
    claiming = false;
    error = null;
    remaining = Duration.zero;
    _notify();
  }

  void _applyClaim(DailyRewardClaim claim) {
    final current = status;
    _applyStatus(
      DailyRewardStatus(
        available: false,
        amount: current?.amount ?? claim.claimedAmount,
        nextClaimAt: claim.nextClaimAt,
        serverTime: claim.serverTime,
        availableBalance: claim.availableBalance,
        lockedBalance: claim.lockedBalance,
      ),
    );
  }

  void _applyStatus(DailyRewardStatus value) {
    if (_disposed) return;
    status = value;
    _timer?.cancel();
    _timer = null;
    _remainingAtFetch = null;
    _stopwatch = null;
    _refreshAtZero = false;

    if (value.available || value.nextClaimAt == null) {
      remaining = Duration.zero;
      return;
    }

    final duration = value.nextClaimAt!.difference(value.serverTime);
    _remainingAtFetch = duration.isNegative ? Duration.zero : duration;
    remaining = _remainingAtFetch!;
    _stopwatch = Stopwatch()..start();

    if (remaining == Duration.zero) {
      _scheduleRefreshAtZero();
    } else {
      _timer = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
    }
  }

  void _tick() {
    final base = _remainingAtFetch;
    final stopwatch = _stopwatch;
    if (base == null || stopwatch == null) return;

    final value = base - stopwatch.elapsed;
    if (value <= Duration.zero) {
      remaining = Duration.zero;
      _timer?.cancel();
      _timer = null;
      _notify();
      _scheduleRefreshAtZero();
      return;
    }
    remaining = value;
    _notify();
  }

  void _scheduleRefreshAtZero() {
    if (_refreshAtZero || _disposed) return;
    _refreshAtZero = true;
    _timer = Timer(const Duration(seconds: 1), () {
      _timer = null;
      unawaited(refresh());
    });
  }

  Future<void> _clearKey(String account) async {
    try {
      await _keyStore.clear(account);
    } on DailyRewardKeyStorageException {
      // A consumed key is not reused within this session.
    }
  }

  String _messageFor(Object failure) {
    if (failure is IdentityApiException) {
      return switch (failure.code) {
        'coins_unavailable' => 'Coins are temporarily unavailable. Try again.',
        'invalid_access_token' => 'Sign in again.',
        _ => failure.message.isEmpty
            ? 'Could not load the daily bonus.'
            : failure.message,
      };
    }
    if (failure is IdentityNetworkException) {
      return 'Cannot reach the server. Try again.';
    }
    if (failure is SessionRequestException) {
      return 'Your session has changed. Sign in again.';
    }
    return 'Could not load the daily bonus.';
  }

  bool _active(int generation) => !_disposed && generation == _generation;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    super.dispose();
  }
}
