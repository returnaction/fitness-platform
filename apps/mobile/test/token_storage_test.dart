import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stores the complete token pair in one secure value', () async {
    final store = _FakeSecureValueStore();
    final storage = TokenStorage(store);

    await storage.write(
      const SessionTokens(accessToken: 'access', refreshToken: 'refresh'),
    );

    expect(store.values, hasLength(1));
    expect(store.values['auth_session_v1'], isNotNull);
    expect(await storage.read(), isA<SessionTokens>());
  });

  test(
    'removes a partial value after a secure-storage write failure',
    () async {
      final store = _FakeSecureValueStore()..failWrites = true;
      final storage = TokenStorage(store);

      await expectLater(
        storage.write(
          const SessionTokens(accessToken: 'access', refreshToken: 'refresh'),
        ),
        throwsA(isA<TokenStorageException>()),
      );

      expect(store.deletedKeys, ['auth_session_v1']);
    },
  );
}

class _FakeSecureValueStore implements SecureValueStore {
  final values = <String, String>{};
  final deletedKeys = <String>[];
  bool failWrites = false;

  @override
  Future<void> delete(String key) async {
    deletedKeys.add(key);
    values.remove(key);
  }

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async {
    if (failWrites) {
      throw StateError('storage failure');
    }
    values[key] = value;
  }
}
