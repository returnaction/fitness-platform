import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_controller.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/home/home_controller.dart';
import 'package:fitness_app/features/home/home_screen.dart';
import 'package:fitness_app/features/home/wallet_balance.dart';
import 'package:fitness_app/features/home/wallet_repository.dart';
import 'package:fitness_app/features/home/wallet_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Home shows available and locked balances in the AppBar', (tester) async {
    final store = WalletStore(Disk());
    final controller = HomeController(WalletApi(), store, () async => 'account');
    addTearDown(controller.dispose);
    addTearDown(store.dispose);
    await controller.open();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        homeControllerProvider.overrideWithValue(controller),
        walletProvider.overrideWith(
          (ref) async => const WalletBalance(availableBalance: 120, lockedBalance: 20),
        ),
        authControllerProvider.overrideWith(() => TestAuth()),
      ],
      child: const MaterialApp(home: HomeScreen()),
    ));
    await tester.pumpAndSettle();

    expect(find.text('120'), findsOneWidget);
    expect(find.text('20'), findsOneWidget);
    expect(find.byTooltip('Available coins'), findsOneWidget);
    expect(find.byTooltip('Locked coins'), findsOneWidget);
  });
}

class WalletApi implements WalletRepository {
  @override
  Future<WalletBalance> getOwnWalletBalance() async =>
      const WalletBalance(availableBalance: 120, lockedBalance: 20);
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

class Disk implements SecureValueStore {
  @override
  Future<void> delete(String key) async {}

  @override
  Future<String?> read(String key) async => null;

  @override
  Future<void> write(String key, String value) async {}
}
