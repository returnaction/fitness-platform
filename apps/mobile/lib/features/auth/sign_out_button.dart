import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'auth_controller.dart';

class SignOutButton extends ConsumerWidget {
  const SignOutButton({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => IconButton(
    icon: const Icon(Icons.logout),
    tooltip: 'Sign out',
    onPressed: () => _showSignOutOptions(context, ref),
  );
}

Future<void> _showSignOutOptions(BuildContext context, WidgetRef ref) async {
  final choice = await showModalBottomSheet<_SignOutChoice>(
    context: context,
    builder: (context) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            leading: const Icon(Icons.logout),
            title: const Text('Sign out from this device'),
            subtitle: const Text('Only on this device'),
            onTap: () => Navigator.pop(context, _SignOutChoice.currentDevice),
          ),
          ListTile(
            leading: const Icon(Icons.devices),
            title: const Text('Sign out from all devices'),
            subtitle: const Text('End every active session'),
            onTap: () => Navigator.pop(context, _SignOutChoice.allDevices),
          ),
          ListTile(
            title: const Center(child: Text('Cancel')),
            onTap: () => Navigator.pop(context),
          ),
        ],
      ),
    ),
  );
  if (choice == null || !context.mounted) {
    return;
  }
  if (choice == _SignOutChoice.allDevices) {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Sign out from all devices?'),
        content: const Text(
          'This will end every active session, including this one.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Sign out from all devices'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }
  }
  final controller = ref.read(authControllerProvider.notifier);
  final messenger = ScaffoldMessenger.of(context);
  final result = choice == _SignOutChoice.allDevices
      ? await controller.logoutAll()
      : await controller.logout();
  if (!messenger.mounted) return;
  if (result.localTokensCleared) {
    if (!result.remoteSucceeded) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            choice == _SignOutChoice.allDevices
                ? 'You have been signed out on this device. Other devices may still be signed in.'
                : 'You have been signed out on this device.',
          ),
        ),
      );
    }
    return;
  }
  messenger.showSnackBar(
    const SnackBar(content: Text('Local sign out could not be confirmed.')),
  );
}

enum _SignOutChoice { currentDevice, allDevices }
