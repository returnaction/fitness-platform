import 'package:fitness_app/features/home/wallet_balance.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('wallet balance accepts non-negative int64 values', () {
    final balance = WalletBalance.fromJson({
      'availableBalance': 9223372036854775807,
      'lockedBalance': 0,
    });
    expect(balance.availableBalance, 9223372036854775807);
    expect(balance.lockedBalance, 0);
  });

  test('wallet balance rejects missing, negative and non-integer values', () {
    for (final json in [
      <String, dynamic>{},
      {'availableBalance': -1, 'lockedBalance': 0},
      {'availableBalance': 0, 'lockedBalance': -1},
      {'availableBalance': 1.5, 'lockedBalance': 0},
      {'availableBalance': '1', 'lockedBalance': 0},
    ]) {
      expect(() => WalletBalance.fromJson(json), throwsFormatException);
    }
  });

  test('coin formatting groups digits without changing the value', () {
    expect(formatCoins(0), '0');
    expect(formatCoins(120), '120');
    expect(formatCoins(1200), '1,200');
    expect(formatCoins(9223372036854775807), '9,223,372,036,854,775,807');
  });
}
