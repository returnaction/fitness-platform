import 'package:fitness_app/features/coins/daily_reward_controller.dart';
import 'package:fitness_app/features/coins/daily_reward_repository.dart';
import 'package:fitness_app/features/coins/model/daily_reward_status.dart';
import 'package:fitness_app/features/coins/model/daily_reward_claim.dart';
import 'package:fitness_app/features/auth/auth_controller.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/home/home_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Home shows available and locked balances in the AppBar', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dailyRewardRepositoryProvider.overrideWithValue(RewardApi()),
        authControllerProvider.overrideWith(() => TestAuth()),
      ],
      child: const MaterialApp(home: HomeScreen()),
    ));
    await tester.pumpAndSettle();

    expect(find.byTooltip('Sign out'), findsNothing);
    expect(find.byTooltip('Claim daily reward'), findsOneWidget);
    expect(find.text('120'), findsOneWidget);
    expect(find.text('20'), findsOneWidget);
    expect(find.byTooltip('Available coins: 120'), findsOneWidget);
    expect(find.byTooltip('Locked coins: 20'), findsOneWidget);
  });
}

class RewardApi implements DailyRewardRepository {
  @override
  Future<DailyRewardStatus> getStatus() async => DailyRewardStatus(
    available: true, amount: 10, nextClaimAt: null,
    serverTime: DateTime.utc(2026, 11, 17), availableBalance: 120, lockedBalance: 20,
  );
  @override
  Future<DailyRewardClaim> claim(String key) => throw UnimplementedError();
}

class TestAuth extends AuthController {
  @override
  Future<AuthStatus> build() async => AuthStatus.authenticated;

  @override
  Future<LogoutResult> logout() async =>
      const LogoutResult(remoteSucceeded: true, localTokensCleared: true);

  @override
  Future<LogoutResult> logoutAll() async =>
      const LogoutResult(remoteSucceeded: true, localTokensCleared: true);
}
