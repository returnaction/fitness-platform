import 'package:fitness_app/features/coins/model/daily_reward_claim.dart';
import 'package:fitness_app/features/coins/model/daily_reward_status.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('parses available status with null claim and next time', () {
    final status = DailyRewardStatus.fromJson({
      'available': true,
      'amount': 10,
      'nextClaimAt': null,
      'serverTime': '2026-11-16T12:34:56Z',
      'availableBalance': 10,
      'lockedBalance': 0,
    });

    expect(status.available, isTrue);
    expect(status.amount, 10);
    expect(status.nextClaimAt, isNull);
    expect(status.serverTime, DateTime.utc(2026, 11, 16, 12, 34, 56));
    expect(status.availableBalance, 10);
  });

  test('parses unavailable status and claim result', () {
    final status = DailyRewardStatus.fromJson({
      'available': false,
      'amount': 10,
      'nextClaimAt': '2026-11-17T12:34:56Z',
      'serverTime': '2026-11-16T12:34:56Z',
      'availableBalance': 20,
      'lockedBalance': 2,
    });
    final claim = DailyRewardClaim.fromJson({
      'claimedAmount': 10,
      'availableBalance': 20,
      'lockedBalance': 2,
      'granted': true,
      'nextClaimAt': '2026-11-17T12:34:56Z',
      'serverTime': '2026-11-16T12:34:56Z',
    });

    expect(status.available, isFalse);
    expect(status.nextClaimAt, DateTime.utc(2026, 11, 17, 12, 34, 56));
    expect(claim.claimedAmount, 10);
    expect(claim.granted, isTrue);
  });

  test('rejects missing and wrongly typed fields', () {
    expect(
      () => DailyRewardStatus.fromJson({
        'available': 'true',
        'amount': 10,
        'nextClaimAt': null,
        'serverTime': '2026-11-16T12:34:56Z',
        'availableBalance': 10,
        'lockedBalance': 0,
      }),
      throwsFormatException,
    );
    expect(
      () => DailyRewardClaim.fromJson({
        'claimedAmount': -1,
        'availableBalance': 20,
        'lockedBalance': 0,
        'granted': true,
        'nextClaimAt': '2026-11-17T12:34:56Z',
        'serverTime': '2026-11-16T12:34:56Z',
      }),
      throwsFormatException,
    );
  });

  test('formats timer as HH:MM:SS and clamps negatives', () {
    expect(
      formatDailyRewardRemaining(
        const Duration(hours: 1, minutes: 2, seconds: 3),
      ),
      '01:02:03',
    );
    expect(
      formatDailyRewardRemaining(const Duration(seconds: -5)),
      '00:00:00',
    );
  });
}
