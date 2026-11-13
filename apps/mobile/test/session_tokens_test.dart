import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('wire format uses camelCase only', () {
    const tokens = SessionTokens(accessToken: 'a', refreshToken: 'r');
    expect(tokens.toJson(), {'accessToken': 'a', 'refreshToken': 'r'});
    expect(
      () => SessionTokens.fromJson({'access_token': 'a', 'refresh_token': 'r'}),
      throwsFormatException,
    );
  });

  test('reads the token pair returned by Identity', () {
    final tokens = SessionTokens.fromJson({
      'accessToken': 'access',
      'refreshToken': 'refresh',
    });

    expect(tokens.accessToken, 'access');
    expect(tokens.refreshToken, 'refresh');
  });

  test('rejects a response without a complete token pair', () {
    expect(
      () => SessionTokens.fromJson({'accessToken': 'access'}),
      throwsFormatException,
    );
  });
}
