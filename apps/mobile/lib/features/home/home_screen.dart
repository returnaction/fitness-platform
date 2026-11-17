import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../coins/daily_reward_controller.dart';
import '../coins/daily_reward_header.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) ref.read(dailyRewardControllerProvider).open();
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && mounted) {
      ref.read(dailyRewardControllerProvider).refresh();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(dailyRewardControllerProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Home'),
        actions: [DailyRewardHeader(controller: controller)],
      ),
      body: RefreshIndicator(
        onRefresh: controller.refresh,
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
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text('Pull down to update your balance.'),
            ),
          ],
        ),
      ),
    );
  }
}
