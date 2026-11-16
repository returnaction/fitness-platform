import 'package:flutter/material.dart';

import 'daily_reward_controller.dart';
import 'model/daily_reward_status.dart';

class DailyRewardCard extends StatelessWidget {
  const DailyRewardCard({super.key, required this.controller});

  final DailyRewardController controller;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final status = controller.status;
        final theme = Theme.of(context);
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.card_giftcard,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(width: 8),
                    Text('Daily bonus', style: theme.textTheme.titleMedium),
                  ],
                ),
                const SizedBox(height: 16),
                if (status != null) ...[
                  Row(
                    children: [
                      const Text('Balance'),
                      const Spacer(),
                      Text(
                        '${status.availableBalance} coins',
                        style: theme.textTheme.titleMedium,
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${status.amount} coins per day',
                    style: theme.textTheme.bodySmall,
                  ),
                ],
                if (status == null && controller.error == null)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Text(
                      'Loading...',
                      textAlign: TextAlign.center,
                    ),
                  ),
                if (status == null && controller.error != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Column(
                      children: [
                        Text(
                          controller.error!,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 8),
                        TextButton(
                          onPressed: controller.refresh,
                          child: const Text('Try again'),
                        ),
                      ],
                    ),
                  ),
                if (status != null && controller.error != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    controller.error!,
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                      onPressed: controller.refresh,
                      child: const Text('Try again'),
                    ),
                  ),
                ],
                if (status != null) ...[
                  const SizedBox(height: 16),
                  if (status.available)
                    FilledButton.icon(
                      onPressed: controller.claiming || controller.loading
                          ? null
                          : () => _claim(context),
                      icon: controller.claiming
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.redeem),
                      label: Text(
                        controller.claiming
                            ? 'Claiming...'
                            : 'Claim ${status.amount}',
                      ),
                    )
                  else ...[
                    Text(
                      'Next bonus in',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      formatDailyRewardRemaining(controller.remaining),
                      textAlign: TextAlign.center,
                      style: theme.textTheme.headlineMedium,
                    ),
                    const SizedBox(height: 12),
                    OutlinedButton(
                      onPressed: null,
                      child: const Text('Already claimed'),
                    ),
                  ],
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  Future<void> _claim(BuildContext context) async {
    final claim = await controller.claim();
    if (!context.mounted || claim == null) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          claim.alreadyClaimed
              ? 'Bonus already claimed'
              : 'You received ${claim.claimedAmount} coins',
        ),
      ),
    );
  }
}
