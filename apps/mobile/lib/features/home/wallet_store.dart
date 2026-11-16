import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../core/security/token_storage.dart';
import '../auth/session_operation_coordinator.dart';
import '../profile/durable_profile_storage.dart';
import 'wallet_balance.dart';

final walletStoreProvider = Provider<WalletStore>((ref) {
  final store = WalletStore(
    DurableProfileStorage(FlutterSecureValueStore(const FlutterSecureStorage())),
  );
  ref.onDispose(store.dispose);
  return store;
});

class WalletStore extends ChangeNotifier {
  WalletStore(this._store);

  static const _key = 'own_wallet_v1';
  final SecureValueStore _store;
  final _queue = SessionOperationCoordinator();
  int _generation = 0;
  int get generation => _generation;

  void invalidate() {
    _generation++;
    notifyListeners();
  }

  Future<WalletBalance?> read(String accountId, int generation) =>
      _queue.run(() async {
        _check(generation);
        try {
          final value = await _store.read(_key);
          _check(generation);
          if (value == null) return null;
          final decoded = jsonDecode(value);
          if (decoded is! Map || decoded['accountId'] != accountId) {
            await _store.delete(_key);
            _check(generation);
            return null;
          }
          return WalletBalance.fromJson(Map<String, dynamic>.from(decoded));
        } on StaleWalletOperation {
          rethrow;
        } on FormatException {
          await _deleteCorrupted(generation);
          return null;
        } catch (_) {
          throw const WalletStorageException();
        }
      });

  Future<void> write(String accountId, WalletBalance balance, int generation) =>
      _queue.run(() async {
        _check(generation);
        try {
          await _store.write(_key, jsonEncode({
            'accountId': accountId,
            'availableBalance': balance.availableBalance,
            'lockedBalance': balance.lockedBalance,
          }));
          _check(generation);
        } on StaleWalletOperation {
          rethrow;
        } catch (_) {
          throw const WalletStorageException();
        }
      });

  Future<void> clear() => _queue.run(() async {
    try {
      await _store.delete(_key);
    } catch (_) {
      throw const WalletStorageException();
    }
  });

  Future<void> _deleteCorrupted(int generation) async {
    try {
      await _store.delete(_key);
      _check(generation);
    } on StaleWalletOperation {
      rethrow;
    } catch (_) {
      throw const WalletStorageException();
    }
  }

  void _check(int generation) {
    if (generation != _generation) throw const StaleWalletOperation();
  }
}

class StaleWalletOperation implements Exception {
  const StaleWalletOperation();
}

class WalletStorageException implements Exception {
  const WalletStorageException();
}
