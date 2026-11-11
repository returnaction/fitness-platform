import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/account_screen.dart';
import '../features/auth/auth_controller.dart';
import '../features/auth/auth_form_screen.dart';
import '../features/auth/welcome_screen.dart';

class FitnessApp extends ConsumerWidget {
  const FitnessApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp(
      title: 'Fitness App',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const AuthGate(),
      routes: {
        AppRoute.signIn.path: (_) =>
            const AuthFormScreen(isRegistration: false),
        AppRoute.register.path: (_) =>
            const AuthFormScreen(isRegistration: true),
      },
    );
  }
}

class AuthGate extends ConsumerWidget {
  const AuthGate({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(authControllerProvider);
    return switch (state) {
      AsyncLoading() => const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      ),
      AsyncError() => _RestoreFailure(
        onRetry: () => ref.read(authControllerProvider.notifier).restore(),
      ),
      AsyncData(value: AuthStatus.authenticated) => const AccountScreen(),
      AsyncData(value: AuthStatus.unavailable) => _RestoreFailure(
        onRetry: () => ref.read(authControllerProvider.notifier).restore(),
      ),
      AsyncData() => const WelcomeScreen(),
    };
  }
}

class _RestoreFailure extends StatelessWidget {
  const _RestoreFailure({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Cannot connect to Fitness App.'),
              const SizedBox(height: 16),
              FilledButton(onPressed: onRetry, child: const Text('Try again')),
            ],
          ),
        ),
      ),
    );
  }
}

enum AppRoute {
  signIn('/sign-in'),
  register('/register');

  const AppRoute(this.path);

  final String path;
}
