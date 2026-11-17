import 'dart:async';

import 'package:fitness_app/features/coins/daily_reward_controller.dart';
import 'package:fitness_app/features/coins/daily_reward_header.dart';
import 'package:fitness_app/features/coins/daily_reward_key_store.dart';
import 'package:fitness_app/features/coins/daily_reward_repository.dart';
import 'package:fitness_app/features/coins/model/daily_reward_claim.dart';
import 'package:fitness_app/features/coins/model/daily_reward_status.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uuid/uuid.dart';

void main() {
  testWidgets(
    'gift updates header once; notifications disappear after five seconds',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repository = _Repository();
      final controller = DailyRewardController(
        repository,
        DailyRewardKeyStore(_MemoryStore()),
        () async => 'user',
        const Uuid(),
      );
      await controller.open();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            appBar: AppBar(
              title: const Text('Home'),
              actions: [DailyRewardHeader(controller: controller)],
            ),
          ),
        ),
      );
      expect(tester.takeException(), isNull);
      expect(
        tester.getCenter(find.byIcon(Icons.card_giftcard)).dx,
        greaterThan(tester.getCenter(find.text('7')).dx),
      );
      await tester.tap(find.byTooltip('Claim daily reward'));
      await tester.pump();
      expect(repository.claims, 1);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      repository.pending.complete(
        DailyRewardClaim(
          granted: true,
          claimedAmount: 10,
          availableBalance: 30,
          lockedBalance: 7,
          nextClaimAt: repository.now.add(const Duration(hours: 24)),
          serverTime: repository.now,
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('30'), findsOneWidget);
      expect(find.text('7'), findsOneWidget);
      expect(repository.reads, 1);
      expect(find.text('You received 10 coins'), findsOneWidget);
      await tester.pump(const Duration(seconds: 4));
      expect(find.text('You received 10 coins'), findsOneWidget);
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(SnackBar), findsNothing);
      expect(find.byIcon(Icons.check), findsOneWidget);
      await tester.tap(find.byTooltip('Next daily reward'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('Next bonus in'), findsOneWidget);
      expect(repository.claims, 1);
      await tester.pump(const Duration(seconds: 5));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(SnackBar), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
}

class _Repository implements DailyRewardRepository {
  final now = DateTime.utc(2026, 11, 17, 17);
  final pending = Completer<DailyRewardClaim>();
  int reads = 0;
  int claims = 0;
  @override
  Future<DailyRewardStatus> getStatus() async {
    reads++;
    return DailyRewardStatus(
      available: true,
      amount: 10,
      nextClaimAt: null,
      serverTime: now,
      availableBalance: 20,
      lockedBalance: 7,
    );
  }

  @override
  Future<DailyRewardClaim> claim(String idempotencyKey) {
    claims++;
    return pending.future;
  }
}

class _MemoryStore implements SecureValueStore {
  final values = <String, String>{};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}
