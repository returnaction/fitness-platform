import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../auth/sign_out_button.dart';
import 'wallet_balance.dart';
import 'wallet_repository.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final wallet = ref.watch(walletProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Home'),
        actions: [
          _WalletActions(wallet: wallet),
          const SignOutButton(),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(walletProvider);
          await ref.read(walletProvider.future);
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 16),
            Text(
              'Your fitness journey',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            const Padding(padding: EdgeInsets.only(top: 8), child: Text('Pull down to update your balance.')),
          ],
        ),
      ),
    );
  }
}

class _WalletActions extends ConsumerWidget {
  const _WalletActions({required this.wallet});
  final AsyncValue<WalletBalance> wallet;

  @override
  Widget build(BuildContext context, WidgetRef ref) => wallet.when(
    data: (balance) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _BalanceValue(icon: Icons.monetization_on_outlined, value: balance.availableBalance, tooltip: 'Available coins'),
        const VerticalDivider(indent: 12, endIndent: 12),
        _BalanceValue(icon: Icons.lock_outline, value: balance.lockedBalance, tooltip: 'Locked coins'),
      ],
    ),
    loading: () => const Padding(
      padding: EdgeInsets.symmetric(horizontal: 12),
      child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
    ),
    error: (_, _) => IconButton(
      icon: const Icon(Icons.refresh), tooltip: 'Reload wallet',
      onPressed: () => ref.invalidate(walletProvider),
    ),
  );
}

class _BalanceValue extends StatelessWidget {
  const _BalanceValue({
    required this.icon,
    required this.value,
    required this.tooltip,
  });

  final IconData icon;
  final int value;
  final String tooltip;

  @override
  Widget build(BuildContext context) => Tooltip(
    message: tooltip,
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 18),
        const SizedBox(width: 3),
        Text(formatCoins(value)),
      ],
    ),
  );
}

