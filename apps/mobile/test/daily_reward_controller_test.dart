import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/coins/daily_reward_controller.dart';
import 'package:fitness_app/features/coins/daily_reward_key_store.dart';
import 'package:fitness_app/features/coins/daily_reward_repository.dart';
import 'package:fitness_app/features/coins/model/daily_reward_claim.dart';
import 'package:fitness_app/features/coins/model/daily_reward_status.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:uuid/uuid.dart';

void main() {
  final serverTime = DateTime.utc(2026, 11, 16, 12, 34, 56);
  final nextClaimAt = serverTime.add(const Duration(hours: 1));

  DailyRewardStatus status({required bool available}) => DailyRewardStatus(
    available: available,
    amount: 10,
    claimedForDate: available ? null : '2026-11-16',
    nextClaimAt: available ? null : nextClaimAt,
    serverTime: serverTime,
    availableBalance: available ? 10 : 20,
    lockedBalance: 0,
  );

  DailyRewardClaim claim() => DailyRewardClaim(
    claimedAmount: 10,
    availableBalance: 20,
    lockedBalance: 0,
    claimedForDate: '2026-11-16',
    alreadyClaimed: false,
    nextClaimAt: nextClaimAt,
    serverTime: serverTime,
  );

  test('open loads status and exposes available balance', () async {
    final repository = _FakeRepository()..status = status(available: true);
    final store = _MemorySecureValueStore();
    final controller = _controller(repository, store);
    addTearDown(controller.dispose);

    await controller.open();

    expect(controller.status?.available, isTrue);
    expect(controller.status?.availableBalance, 10);
    expect(controller.loading, isFalse);
    expect(repository.statusCalls, 1);
  });

  test('claim updates balance and starts countdown without second GET', () async {
    final repository = _FakeRepository()
      ..status = status(available: true)
      ..claimResult = claim();
    final store = _MemorySecureValueStore();
    final controller = _controller(repository, store);
    addTearDown(controller.dispose);

    await controller.open();
    final result = await controller.claim();

    expect(result, isNotNull);
    expect(repository.statusCalls, 1);
    expect(repository.claimKeys, hasLength(1));
    expect(controller.status?.available, isFalse);
    expect(controller.status?.availableBalance, 20);
    expect(controller.remaining, greaterThan(Duration.zero));
    expect(store.values, isEmpty);
  });

  test('retry after network failure reuses the same idempotency key', () async {
    final repository = _FakeRepository()
      ..status = status(available: true)
      ..claimError = const IdentityNetworkException();
    final store = _MemorySecureValueStore();
    final controller = _controller(repository, store);
    addTearDown(controller.dispose);

    await controller.open();
    expect(await controller.claim(), isNull);
    expect(repository.claimKeys, hasLength(1));
    final firstKey = repository.claimKeys.single;
    expect(store.values.values.single, firstKey);

    repository
      ..claimError = null
      ..claimResult = claim();
    expect(await controller.claim(), isNotNull);
    expect(repository.claimKeys, [firstKey, firstKey]);
    expect(store.values, isEmpty);
  });

  test('invalid key is discarded before the next attempt', () async {
    final repository = _FakeRepository()
      ..status = status(available: true)
      ..claimError = const IdentityApiException(
        statusCode: 400,
        code: 'invalid_request',
        message: 'Check the request format.',
      );
    final store = _MemorySecureValueStore();
    final controller = _controller(repository, store);
    addTearDown(controller.dispose);

    await controller.open();
    expect(await controller.claim(), isNull);
    expect(store.values, isEmpty);
  });

  test('claim does nothing while the reward is unavailable', () async {
    final repository = _FakeRepository()..status = status(available: false);
    final store = _MemorySecureValueStore();
    final controller = _controller(repository, store);
    addTearDown(controller.dispose);

    await controller.open();
    expect(await controller.claim(), isNull);
    expect(repository.claimKeys, isEmpty);
  });
}

DailyRewardController _controller(
  _FakeRepository repository,
  _MemorySecureValueStore store,
) => DailyRewardController(
  repository,
  DailyRewardKeyStore(store),
  () async => 'account-id',
  const Uuid(),
);

class _FakeRepository implements DailyRewardRepository {
  DailyRewardStatus status = DailyRewardStatus(
    available: true,
    amount: 10,
    claimedForDate: null,
    nextClaimAt: null,
    serverTime: DateTime.utc(2026, 11, 16, 12, 34, 56),
    availableBalance: 0,
    lockedBalance: 0,
  );
  DailyRewardClaim? claimResult;
  Object? claimError;
  int statusCalls = 0;
  final List<String> claimKeys = [];

  @override
  Future<DailyRewardStatus> getStatus() async {
    statusCalls++;
    return status;
  }

  @override
  Future<DailyRewardClaim> claim(String idempotencyKey) async {
    claimKeys.add(idempotencyKey);
    final failure = claimError;
    if (failure != null) throw failure;
    return claimResult!;
  }
}

class _MemorySecureValueStore implements SecureValueStore {
  final Map<String, String> values = {};

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }
}
