import 'dart:async';

import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/home/home_controller.dart';
import 'package:fitness_app/features/home/wallet_balance.dart';
import 'package:fitness_app/features/home/wallet_repository.dart';
import 'package:fitness_app/features/home/wallet_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('open reads one current wallet snapshot', () async {
    final api = WalletApi();
    final controller = HomeController(api, WalletStore(Disk()), () async => 'a');
    addTearDown(controller.dispose);

    await controller.open();

    expect(api.reads, 1);
    expect(controller.balance?.availableBalance, 120);
    expect(controller.balance?.lockedBalance, 20);
    expect(controller.loading, isFalse);
    expect(controller.error, isNull);
  });

  test('concurrent refreshes share one request', () async {
    final pending = Completer<WalletBalance>();
    final api = WalletApi()..read = () => pending.future;
    final controller = HomeController(api, WalletStore(Disk()), () async => 'a');
    addTearDown(controller.dispose);

    final first = controller.refresh();
    final second = controller.refresh();
    await Future<void>.delayed(Duration.zero);
    expect(api.reads, 1);
    pending.complete(const WalletBalance(availableBalance: 7, lockedBalance: 3));
    await Future.wait([first, second]);

    expect(controller.balance?.availableBalance, 7);
  });

  test('failure does not invent a balance and retry fetches again', () async {
    final api = WalletApi()..read = () async => throw StateError('offline');
    final controller = HomeController(api, WalletStore(Disk()), () async => 'a');
    addTearDown(controller.dispose);

    await controller.open();
    expect(controller.balance, isNull);
    expect(controller.error, isA<StateError>());

    api.read = () async => const WalletBalance(availableBalance: 2, lockedBalance: 1);
    await controller.refresh();
    expect(api.reads, 2);
    expect(controller.balance?.availableBalance, 2);
    expect(controller.error, isNull);
  });

  test('session invalidation clears balance and drops a late response', () async {
    final pending = Completer<WalletBalance>();
    final store = WalletStore(Disk());
    final controller = HomeController(
      WalletApi()..read = () => pending.future,
      store,
      () async => 'a',
    );
    addTearDown(controller.dispose);
    addTearDown(store.dispose);

    final load = controller.open();
    store.invalidate();
    pending.complete(const WalletBalance(availableBalance: 5, lockedBalance: 1));
    await load;

    expect(controller.balance, isNull);
    expect(controller.error, isNull);
  });

  test('cached balance is visible before the new request finishes', () async {
    final disk = Disk();
    final store = WalletStore(disk);
    await store.write('a', const WalletBalance(availableBalance: 9, lockedBalance: 4), 0);
    final pending = Completer<WalletBalance>();
    final controller = HomeController(
      WalletApi()..read = () => pending.future,
      store,
      () async => 'a',
    );
    addTearDown(controller.dispose);
    addTearDown(store.dispose);

    final opening = controller.open();
    await Future<void>.delayed(Duration.zero);
    expect(controller.balance?.availableBalance, 9);
    expect(controller.loading, isTrue);
    pending.complete(const WalletBalance(availableBalance: 10, lockedBalance: 2));
    await opening;

    expect(controller.balance?.availableBalance, 10);
  });

  test('a reset does not leave a completed request blocking the next load', () async {
    final firstReply = Completer<WalletBalance>();
    final secondReply = Completer<WalletBalance>();
    late final WalletApi api;
    api = WalletApi()..read = () => api.reads == 1
        ? firstReply.future
        : secondReply.future;
    final store = WalletStore(Disk());
    final controller = HomeController(api, store, () async => 'a');
    addTearDown(controller.dispose);
    addTearDown(store.dispose);

    final first = controller.open();
    await Future<void>.delayed(Duration.zero);
    store.invalidate();
    firstReply.complete(const WalletBalance(availableBalance: 1, lockedBalance: 2));
    await first;

    final second = controller.open();
    await Future<void>.delayed(Duration.zero);
    expect(api.reads, 2);
    secondReply.complete(const WalletBalance(availableBalance: 3, lockedBalance: 4));
    await second;
    expect(controller.balance?.availableBalance, 3);
  });
}

class WalletApi implements WalletRepository {
  int reads = 0;
  Future<WalletBalance> Function() read =
      () async => const WalletBalance(availableBalance: 120, lockedBalance: 20);

  @override
  Future<WalletBalance> getOwnWalletBalance() {
    reads++;
    return read();
  }
}

class Disk implements SecureValueStore {
  final values = <String, String>{};
  @override
  Future<void> delete(String key) async { values.remove(key); }

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async { values[key] = value; }
}
