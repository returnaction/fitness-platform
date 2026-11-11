import 'package:flutter/material.dart';

import '../../app/fitness_app.dart';

class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Spacer(),
              Icon(
                Icons.fitness_center,
                size: 72,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(height: 24),
              Text(
                'Fitness App',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 8),
              const Text(
                'Train consistently. Prove your progress.',
                textAlign: TextAlign.center,
              ),
              const Spacer(),
              FilledButton(
                onPressed: () =>
                    Navigator.pushNamed(context, AppRoute.signIn.path),
                child: const Text('Sign in'),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: () =>
                    Navigator.pushNamed(context, AppRoute.register.path),
                child: const Text('Create account'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
