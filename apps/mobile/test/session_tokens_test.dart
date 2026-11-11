import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('reads the token pair returned by Identity', () {
    final tokens = SessionTokens.fromJson({
      'access_token': 'access',
      'refresh_token': 'refresh',
    });

    expect(tokens.accessToken, 'access');
    expect(tokens.refreshToken, 'refresh');
  });

  test('rejects a response without a complete token pair', () {
    expect(
      () => SessionTokens.fromJson({'access_token': 'access'}),
      throwsFormatException,
    );
  });
}
