import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../core/security/token_storage.dart';
import '../auth/session_operation_coordinator.dart';
import 'profile.dart';
import 'durable_profile_storage.dart';

final profileStoreProvider = Provider<ProfileStore>((ref) {
  final store = ProfileStore(
    DurableProfileStorage(FlutterSecureValueStore(const FlutterSecureStorage())),
  );
  ref.onDispose(store.dispose);
  return store;
});

class StoredProfile {
  const StoredProfile({required this.profile, required this.pendingSave});
  final Profile? profile;
  final bool pendingSave;
}

class ProfileStorageException implements Exception {
  const ProfileStorageException();
}

class StaleProfileOperation implements Exception {
  const StaleProfileOperation();
}

/// Serializes disk access only. Network operations never hold this queue.
class ProfileStore extends ChangeNotifier {
  ProfileStore(this._store);
  static const _key = 'own_profile_v1';
  final SecureValueStore _store;
  final _queue = SessionOperationCoordinator();
  int _generation = 0;
  int get generation => _generation;

  void invalidate() {
    _generation++;
    notifyListeners();
  }

  void check(int generation) {
    if (generation != _generation) throw const StaleProfileOperation();
  }

  Future<StoredProfile?> read(String accountId, int generation) =>
      _queue.run(() async {
        check(generation);
        try {
          final value = await _store.read(_key);
          check(generation);
          if (value == null) return null;
          final decoded = jsonDecode(value);
          if (decoded is! Map || decoded['accountId'] != accountId) {
            await _store.delete(_key);
            check(generation);
            return null;
          }
          final pending = decoded['pendingSave'];
          final data = decoded['profile'];
          if (pending is! bool || (data != null && data is! Map)) {
            throw const FormatException();
          }
          return StoredProfile(
            profile: data == null ? null
                : Profile.fromJson(Map<String, dynamic>.from(data as Map)),
            pendingSave: pending,
          );
        } on StaleProfileOperation {
          rethrow;
        } on FormatException {
          await _deleteCorrupted(generation);
          return null;
        } catch (_) {
          throw const ProfileStorageException();
        }
      });

  Future<void> write(String accountId, StoredProfile value, int generation) =>
      _queue.run(() async {
        check(generation);
        try {
          await _store.write(_key, jsonEncode({
            'accountId': accountId,
            'profile': value.profile?.toJson(),
            'pendingSave': value.pendingSave,
          }));
          check(generation);
        } on StaleProfileOperation {
          rethrow;
        } catch (_) {
          throw const ProfileStorageException();
        }
      });

  Future<void> clear() => _queue.run(() async {
    try {
      await _store.delete(_key);
    } catch (_) {
      throw const ProfileStorageException();
    }
  });

  Future<void> _deleteCorrupted(int generation) async {
    try {
      await _store.delete(_key);
      check(generation);
    } on StaleProfileOperation {
      rethrow;
    } catch (_) {
      throw const ProfileStorageException();
    }
  }
}
