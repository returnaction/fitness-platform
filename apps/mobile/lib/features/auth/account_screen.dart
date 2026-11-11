import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'auth_controller.dart';

class AccountScreen extends ConsumerWidget {
  const AccountScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Sign out',
            onPressed: () => _showSignOutOptions(context, ref),
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircleAvatar(
              radius: 38,
              backgroundColor: Theme.of(context).colorScheme.primary,
              foregroundColor: Theme.of(context).colorScheme.onPrimary,
              child: const Icon(Icons.check, size: 40),
            ),
            const SizedBox(height: 24),
            Text(
              'You are signed in',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
          ],
        ),
      ),
    );
  }
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
            title: const Text('Log out'),
            subtitle: const Text('Only on this device'),
            onTap: () => Navigator.pop(context, _SignOutChoice.currentDevice),
          ),
          ListTile(
            leading: const Icon(Icons.devices),
            title: const Text('Log out from all devices'),
            subtitle: const Text('End every active session'),
            onTap: () => Navigator.pop(context, _SignOutChoice.allDevices),
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
        title: const Text('Log out from all devices?'),
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
            child: const Text('Log out from all devices'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) {
      return;
    }
  }
  final controller = ref.read(authControllerProvider.notifier);
  final remoteSucceeded = choice == _SignOutChoice.allDevices
      ? await controller.logoutAll()
      : await controller.logout();
  if (!remoteSucceeded && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          choice == _SignOutChoice.allDevices
              ? 'You have been signed out on this device. Other devices may still be signed in.'
              : 'You have been signed out on this device.',
        ),
      ),
    );
  }
}

enum _SignOutChoice { currentDevice, allDevices }
