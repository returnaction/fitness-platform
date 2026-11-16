import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../core/security/token_storage.dart';

final savedAccountStoreProvider = Provider<SavedAccountStore>((ref) =>
    SavedAccountStore(FlutterSecureValueStore(const FlutterSecureStorage())));

final savedAccountsProvider = FutureProvider<List<SavedAccount>>(
  (ref) => ref.watch(savedAccountStoreProvider).read(),
);

class SavedAccount {
  const SavedAccount({required this.email, required this.password});
  final String email;
  final String password;
}

class SavedAccountStore {
  SavedAccountStore(this._storage);
  static const _key = 'saved_accounts_v1';
  final SecureValueStore _storage;

  Future<List<SavedAccount>> read() async {
    try {
      final raw = await _storage.read(_key);
      if (raw == null) return const [];
      final decoded = jsonDecode(raw);
      if (decoded is! List) return const [];
      return decoded.whereType<Map>().map((item) {
        final email = item['email'];
        final password = item['password'];
        if (email is! String || password is! String) throw const FormatException();
        return SavedAccount(email: email, password: password);
      }).toList(growable: false);
    } catch (_) {
      return const [];
    }
  }

  Future<void> save(String email, String password) async {
    final accounts = await read();
    final updated = [
      SavedAccount(email: email, password: password),
      ...accounts.where((account) => account.email != email),
    ];
    await _storage.write(_key, jsonEncode(updated.take(5).map((account) => {
      'email': account.email, 'password': account.password,
    }).toList()));
  }

  Future<void> remove(String email) async {
    final accounts = await read();
    await _storage.write(_key, jsonEncode(accounts
        .where((account) => account.email != email)
        .map((account) => {'email': account.email, 'password': account.password})
        .toList()));
  }
}
