import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../auth/auth_controller.dart';
import 'wallet_balance.dart';
import 'wallet_repository.dart';
import 'wallet_store.dart';

final homeControllerProvider = Provider<HomeController>((ref) {
  final controller = HomeController(
    ref.watch(walletRepositoryProvider),
    ref.watch(walletStoreProvider),
    ref.watch(authRepositoryProvider).profileAccountId,
  );
  ref.onDispose(controller.dispose);
  unawaited(controller.open());
  return controller;
});

class HomeController extends ChangeNotifier {
  HomeController(this._repository, this._store, this._accountId) {
    _store.addListener(_reset);
  }

  final WalletRepository _repository;
  final WalletStore _store;
  final Future<String> Function() _accountId;
  WalletBalance? balance;
  Object? error;
  bool loading = false;
  bool _disposed = false;
  Future<void>? _request;

  Future<void> open() => _start(_open);

  Future<void> refresh() => _start(_refresh);

  Future<void> _start(Future<void> Function() operation) {
    // A session reset can invalidate a request after it has already begun.
    // It is no longer active when [loading] is false, so it must not prevent a
    // later Home creation, Retry, or pull-to-refresh from starting a new GET.
    if (!loading) _request = null;
    return _request ??= operation();
  }

  Future<void> _open() async {
    final generation = _store.generation;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final cached = await _readCached(generation);
      if (cached != null) {
        balance = cached;
        notifyListeners();
      }
      await _fetch(generation);
    } catch (failure) {
      if (_active(generation)) error = failure;
    } finally {
      _finish(generation);
    }
  }

  Future<void> _refresh() async {
    final generation = _store.generation;
    loading = true;
    error = null;
    notifyListeners();
    try {
      await _fetch(generation);
    } catch (failure) {
      if (_active(generation)) error = failure;
    } finally {
      _finish(generation);
    }
  }

  Future<WalletBalance?> _readCached(int generation) async {
    try {
      final accountId = await _accountId().timeout(const Duration(seconds: 1));
      _check(generation);
      return await _store.read(accountId, generation).timeout(
        const Duration(seconds: 1),
      );
    } on TimeoutException {
      return null;
    } on WalletStorageException {
      return null;
    }
  }

  Future<void> _fetch(int generation) async {
    final loaded = await _repository.getOwnWalletBalance();
    _check(generation);
    balance = loaded;
    unawaited(_saveCached(loaded, generation));
  }

  Future<void> _saveCached(WalletBalance value, int generation) async {
    try {
      final accountId = await _accountId().timeout(const Duration(seconds: 1));
      _check(generation);
      await _store.write(accountId, value, generation).timeout(
        const Duration(seconds: 1),
      );
    } on TimeoutException {
      // A local cache must never delay or invalidate a successful server read.
    } on WalletStorageException {
      // The next Home open will simply use the server until storage recovers.
    } on StaleHomeOperation {
      // The session changed before this optional cache write completed.
    }
  }

  void _finish(int generation) {
    if (_active(generation)) {
      loading = false;
      _request = null;
      notifyListeners();
    }
  }

  void _reset() {
    balance = null;
    error = null;
    loading = false;
    _request = null;
    notifyListeners();
  }

  bool _active(int generation) =>
      !_disposed && generation == _store.generation;

  void _check(int generation) {
    if (!_active(generation)) throw const StaleHomeOperation();
  }

  @override
  void dispose() {
    _disposed = true;
    _store.removeListener(_reset);
    super.dispose();
  }
}

class StaleHomeOperation implements Exception {
  const StaleHomeOperation();
}
