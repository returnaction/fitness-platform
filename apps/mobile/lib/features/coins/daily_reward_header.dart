import 'package:flutter/material.dart';

import 'daily_reward_controller.dart';
import 'model/daily_reward_status.dart';

/// Балансы и подарок используют один снимок: ответ claims сразу обновляет шапку.
class DailyRewardHeader extends StatelessWidget {
  const DailyRewardHeader({super.key, required this.controller});

  final DailyRewardController controller;
  static const notificationDuration = Duration(seconds: 5);

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final status = controller.status;
      if (status == null) {
        if (controller.error != null) {
          return IconButton(
            tooltip: 'Reload wallet and bonus',
            icon: const Icon(Icons.refresh),
            onPressed: controller.loading ? null : () => _reload(context),
          );
        }
        return const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        );
      }
      // Ограничиваем только текст сумм: кнопка остаётся удобной для нажатия
      // даже на узком экране. Полная сумма доступна через Tooltip/Semantics.
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _BalanceValue(
            value: status.availableBalance,
            icon: Icons.monetization_on_outlined,
            label: 'Available coins',
          ),
          const SizedBox(height: 22, child: VerticalDivider()),
          _BalanceValue(
            value: status.lockedBalance,
            icon: Icons.lock_outline,
            label: 'Locked coins',
          ),
          IconButton(
            tooltip: status.available
                ? 'Claim daily reward'
                : 'Next daily reward',
            onPressed: controller.claiming || controller.loading
                ? null
                : () => _onGiftPressed(context),
            icon: controller.claiming || controller.loading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Icon(status.available ? Icons.card_giftcard : Icons.check),
          ),
        ],
      );
    },
  );

  Future<void> _reload(BuildContext context) async {
    await controller.refresh();
    if (context.mounted && controller.error != null) {
      _notify(context, Text(controller.error!));
    }
  }

  Future<void> _onGiftPressed(BuildContext context) async {
    if (controller.status?.available != true) {
      _notify(context, _RemainingReward(controller: controller));
      return;
    }
    final claim = await controller.claim();
    if (!context.mounted) return;
    if (claim != null) {
      _notify(
        context,
        claim.granted
            ? Text('You received ${claim.claimedAmount} coins')
            : _RemainingReward(controller: controller),
      );
    } else if (controller.error != null) {
      _notify(context, Text(controller.error!));
    }
  }

  void _notify(BuildContext context, Widget content) {
    // Новое нажатие заменяет сообщение, а не создаёт очередь уведомлений.
    ScaffoldMessenger.of(context)
      ..removeCurrentSnackBar()
      ..showSnackBar(
        SnackBar(duration: notificationDuration, content: content),
      );
  }
}

class _RemainingReward extends StatelessWidget {
  const _RemainingReward({required this.controller});
  final DailyRewardController controller;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) => Text(
      'Next bonus in ${formatDailyRewardRemaining(controller.remaining)}',
    ),
  );
}

class _BalanceValue extends StatelessWidget {
  const _BalanceValue({
    required this.value,
    required this.icon,
    required this.label,
  });
  final int value;
  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => Tooltip(
    message: '$label: $value',
    child: Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 18),
          const SizedBox(width: 3),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 64),
            child: Text('$value', maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
        ],
      ),
    ),
  );
}
