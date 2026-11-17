import '../../core/security/token_storage.dart';

class DailyRewardKeyStore {
  DailyRewardKeyStore(this._storage);

  static const _prefix = 'daily_reward_key_v1_';

  final SecureValueStore _storage;

  Future<String?> read(String accountId) async {
    try {
      return await _storage.read(_prefix + accountId);
    } catch (_) {
      throw const DailyRewardKeyStorageException();
    }
  }

  Future<void> write(String accountId, String key) async {
    try {
      await _storage.write(_prefix + accountId, key);
    } catch (_) {
      throw const DailyRewardKeyStorageException();
    }
  }

  Future<void> clear(String accountId) async {
    try {
      await _storage.delete(_prefix + accountId);
    } catch (_) {
      throw const DailyRewardKeyStorageException();
    }
  }
}

class DailyRewardKeyStorageException implements Exception {
  const DailyRewardKeyStorageException();
}
