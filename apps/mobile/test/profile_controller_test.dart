import 'dart:async';
import 'dart:convert';

import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:fitness_app/features/profile/profile.dart';
import 'package:fitness_app/features/profile/durable_profile_storage.dart';
import 'package:fitness_app/features/profile/profile_controller.dart';
import 'package:fitness_app/features/profile/profile_repository.dart';
import 'package:fitness_app/features/profile/profile_store.dart';
import 'package:flutter_test/flutter_test.dart';

Profile value([int version = 1]) => Profile(nickname: 'Nikita', firstName: null,
  lastName: null, bio: null, dateOfBirth: null, timezone: 'UTC',
  visibility: ProfileVisibility.private, version: version);

void main() {
  test('PUT waits for disk acknowledgement, restart recovers durable marker', () async {
    final disk = Disk();
    var durable = <String, String>{};
    Completer<void>? gate;
    final entered = Completer<void>();
    final storage = DurableProfileStorage(disk, flush: () async {
      if (gate != null) {
        if (!entered.isCompleted) entered.complete();
        await gate.future;
      }
      durable = Map.of(disk.values);
    });
    final api = Api();
    final controller = make(api, ProfileStore(storage));
    await controller.open();
    gate = Completer<void>();
    final reply = Completer<Profile>();
    final sent = Completer<void>();
    api.update = (_) { sent.complete(); return reply.future; };
    final saving = controller.save(value());
    await entered.future;
    expect(api.puts, 0);
    expect(jsonDecode(durable['own_profile_v1']!)['pendingSave'], isFalse);
    gate.complete();
    await sent.future;
    expect(jsonDecode(durable['own_profile_v1']!)['pendingSave'], isTrue);
    controller.dispose();
    reply.completeError(const IdentityNetworkException());
    await expectLater(saving, throwsA(isA<StaleProfileOperation>()));
    final restartedDisk = Disk()..values.addAll(durable);
    final restartedApi = Api()..read = () async => value(2);
    final restarted = make(restartedApi, ProfileStore(restartedDisk));
    await restarted.open();
    expect(restartedApi.gets, 1);
    expect(restartedApi.puts, 0);
    expect(restarted.profile?.version, 2);
    restarted.dispose();
  });

  test('failed disk acknowledgement prevents PUT', () async {
    var fail = false;
    final storage = DurableProfileStorage(Disk(), flush: () async {
      if (fail) throw StateError('disk unavailable');
    });
    final api = Api();
    final controller = make(api, ProfileStore(storage));
    await controller.open();
    fail = true;
    await expectLater(controller.save(value()), throwsA(isA<ProfileStorageException>()));
    expect(api.puts, 0);
    controller.dispose();
  });

  test('logout waits for durable profile deletion', () async {
    final disk = Disk();
    final gate = Completer<void>();
    final entered = Completer<void>();
    final store = ProfileStore(DurableProfileStorage(disk, flush: () async {
      entered.complete();
      await gate.future;
    }));
    var cleared = false;
    final clearing = store.clear().then((_) => cleared = true);
    await entered.future;
    expect(cleared, isFalse);
    gate.complete();
    await clearing;
    expect(cleared, isTrue);
    store.dispose();
  });

  test('cache survives controller/store restart without another GET', () async {
    final disk = Disk();
    final api = Api();
    final first = make(api, ProfileStore(disk));
    await first.open();
    await first.open();
    expect(api.gets, 1);
    first.dispose();
    final second = make(api, ProfileStore(disk));
    await second.open();
    expect(api.gets, 1);
    expect(second.profile?.nickname, 'Nikita');
    await second.refresh();
    expect(api.gets, 2);
  });

  test('another account never receives cached profile', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    await store.write('a', StoredProfile(profile: value(), pendingSave: false), 0);
    expect(await store.read('b', 0), isNull);
    expect(disk.values, isEmpty);
  });

  test('failed refresh keeps displayed and durable profile', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final api = Api();
    final controller = make(api, store);
    await controller.open();
    api.read = () async => throw const IdentityNetworkException();
    await controller.refresh();
    expect(controller.profile?.version, 1);
    expect(controller.error, isA<IdentityNetworkException>());
    expect((await store.read('a', 0))?.profile?.version, 1);
  });

  test('pending marker is written before PUT and cleared with server result', () async {
    final store = ProfileStore(Disk());
    final api = Api();
    final controller = make(api, store);
    await controller.open();
    api.update = (_) async {
      expect((await store.read('a', 0))?.pendingSave, isTrue);
      await controller.open();
      expect(api.gets, 1);
      return value(2);
    };
    await controller.save(value());
    expect(controller.profile?.version, 2);
    expect((await store.read('a', 0))?.pendingSave, isFalse);
  });

  test('interrupted save is recovered by GET after restart, never PUT', () async {
    final disk = Disk();
    final pending = Completer<Profile>();
    final started = Completer<void>();
    final api = Api()..update = (_) { started.complete(); return pending.future; };
    final controller = make(api, ProfileStore(disk));
    await controller.open();
    final saving = controller.save(value());
    final check = expectLater(saving, throwsA(isA<StaleProfileOperation>()));
    await started.future;
    controller.dispose();
    pending.completeError(const IdentityNetworkException());
    await check;
    api.read = () async => value(2);
    final restarted = make(api, ProfileStore(disk));
    await restarted.open();
    expect(api.gets, 2);
    expect(api.puts, 1);
    expect(restarted.profile?.version, 2);
    expect(restarted.pendingSave, isFalse);
  });

  test('failed recovery keeps pending marker and permits manual retry', () async {
    final store = ProfileStore(Disk());
    await store.write('a', StoredProfile(profile: value(), pendingSave: true), 0);
    final api = Api()..read = () async => throw const IdentityNetworkException();
    final controller = make(api, store);
    await controller.open();
    expect(controller.profile?.version, 1);
    expect(controller.pendingSave, isTrue);
    expect((await store.read('a', 0))?.pendingSave, isTrue);
    api.read = () async => value(2);
    await controller.refresh();
    expect(controller.pendingSave, isFalse);
  });

  test('failed marker write prevents PUT', () async {
    final disk = Disk();
    final api = Api();
    final controller = make(api, ProfileStore(disk));
    await controller.open();
    disk.failWrite = true;
    await expectLater(controller.save(value()), throwsA(isA<ProfileStorageException>()));
    expect(api.puts, 0);
    expect(controller.saving, isFalse);
  });

  test('failed persistence after PUT retains pending marker', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final api = Api()..update = (_) async {
      disk.failWrite = true;
      return value(2);
    };
    final controller = make(api, store);
    await controller.open();
    await expectLater(controller.save(value()), throwsA(isA<ProfileStorageException>()));
    expect(controller.pendingSave, isTrue);
    expect((await store.read('a', 0))?.pendingSave, isTrue);
  });

  test('older GET arriving after PUT cannot overwrite new profile', () async {
    final store = ProfileStore(Disk());
    final api = Api();
    final controller = make(api, store);
    await controller.open();
    final pending = Completer<Profile>();
    final started = Completer<void>();
    api.read = () { started.complete(); return pending.future; };
    final refresh = controller.refresh();
    await started.future;
    await controller.save(value());
    pending.complete(value());
    await refresh;
    expect(controller.profile?.version, 2);
    expect((await store.read('a', 0))?.profile?.version, 2);
  });

  test('invalidation drops late response without recreating disk record', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final pending = Completer<Profile>();
    final started = Completer<void>();
    final api = Api()..read = () { started.complete(); return pending.future; };
    final controller = make(api, store);
    final loading = controller.open();
    await started.future;
    store.invalidate();
    await store.clear();
    pending.complete(value());
    await loading;
    expect(controller.profile, isNull);
    expect(disk.values, isEmpty);
  });

  test('logout clears profile and reports deletion failure for retry', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final tokenStorage = TokenStorage(disk);
    await tokenStorage.write(const SessionTokens(accessToken: 'a', refreshToken: 'r'));
    await store.write('a', StoredProfile(profile: value(), pendingSave: true), 0);
    final auth = AuthRepository(AuthApi(), tokenStorage, SessionOperationCoordinator(),
      invalidateSessionData: store.invalidate, clearSessionData: store.clear);
    disk.failProfileDelete = true;
    expect((await auth.logout()).localTokensCleared, isFalse);
    expect(await tokenStorage.read(), isNull);
    expect(store.generation, 1);
    disk.failProfileDelete = false;
    expect(await auth.discardLocalSession(), isTrue);
    expect(disk.values, isEmpty);
  });

  test('missing session clears a persisted profile on startup', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    await store.write('a', StoredProfile(profile: value(), pendingSave: true), 0);
    final auth = AuthRepository(AuthApi(), TokenStorage(disk), SessionOperationCoordinator(),
      invalidateSessionData: store.invalidate, clearSessionData: store.clear);
    expect(await auth.restore(), SessionRestoreResult.guest);
    expect(disk.values, isEmpty);
  });

  test('failed refresh clears persisted profile and loaded state', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final controller = make(Api(), store);
    await controller.open();
    final tokens = TokenStorage(disk);
    await tokens.write(const SessionTokens(accessToken: 'a', refreshToken: 'r'));
    final auth = AuthRepository(AuthApi(), tokens, SessionOperationCoordinator(),
      invalidateSessionData: store.invalidate, clearSessionData: store.clear);
    // The empty refresh response is invalid and cannot restore the session.
    expect(await auth.restore(), SessionRestoreResult.guest);
    expect(controller.profile, isNull);
    expect(disk.values, isEmpty);
  });

  test('definite rejection clears pending marker without losing profile', () async {
    final store = ProfileStore(Disk());
    final api = Api()..update = (_) async => throw const IdentityApiException(
      statusCode: 409, code: 'nickname_already_taken', message: 'Failure');
    final controller = make(api, store);
    await controller.open();
    await expectLater(controller.save(value()), throwsA(isA<IdentityApiException>()));
    expect(controller.profile?.version, 1);
    expect((await store.read('a', 0))?.pendingSave, isFalse);
  });

  test('JWT subject associates local cache without extra HTTP request', () async {
    final storage = TokenStorage(Disk());
    final payload = base64Url.encode(utf8.encode(jsonEncode({'sub': 'account-a'})));
    await storage.write(SessionTokens(accessToken: 'header.$payload.signature', refreshToken: 'r'));
    final auth = AuthRepository(AuthApi(), storage, SessionOperationCoordinator());
    expect(await auth.profileAccountId(), 'account-a');
  });

  test('corrupt record is deleted; failed read is surfaced', () async {
    final disk = Disk()..values['own_profile_v1'] = '{broken';
    final store = ProfileStore(disk);
    expect(await store.read('a', 0), isNull);
    disk.failRead = true;
    await expectLater(store.read('a', 0), throwsA(isA<ProfileStorageException>()));
  });

  for (final failure in <Exception>[
    const IdentityNetworkException(), const FormatException('Malformed reply'),
    const IdentityApiException(statusCode: 504, code: 'upstream_timeout', message: 'Failure'),
  ]) {
    test('uncertain ${failure.runtimeType} is confirmed by GET without another PUT', () async {
      final store = ProfileStore(Disk());
      final api = Api();
      final controller = make(api, store);
      await controller.open();
      final submitted = Profile.fromJson({...value().toJson(), 'nickname': 'Changed'});
      api.update = (_) async => throw failure;
      api.read = () async => Profile.fromJson({...submitted.toJson(), 'version': 2});
      await controller.save(submitted);
      expect(controller.profile?.nickname, 'Changed');
      expect(controller.pendingSave, isFalse);
      expect(controller.needsReload, isFalse);
      expect(controller.error, isNull);
      expect(api.puts, 1);
      expect(api.gets, 2);
      expect((await store.read('a', 0))?.pendingSave, isFalse);
    });
  }

  test('mismatching verification keeps uncertainty until explicit reload', () async {
    final store = ProfileStore(Disk());
    final api = Api();
    final controller = make(api, store);
    await controller.open();
    api.update = (_) async => throw const IdentityNetworkException();
    api.read = () async => value(3);
    final submitted = Profile.fromJson({...value().toJson(), 'nickname': 'Changed'});
    await expectLater(controller.save(submitted), throwsA(isA<ProfileSaveUncertainException>()));
    expect(controller.pendingSave, isTrue);
    expect(controller.needsReload, isTrue);
    expect((await store.read('a', 0))?.pendingSave, isTrue);
    await expectLater(controller.save(submitted), throwsStateError);
    expect(api.puts, 1);
    await controller.refresh();
    expect(controller.pendingSave, isFalse);
    expect(controller.needsReload, isFalse);
    expect(controller.profile?.version, 3);
  });

  test('failed verification retains marker and never retries PUT', () async {
    final store = ProfileStore(Disk());
    final api = Api();
    final controller = make(api, store);
    await controller.open();
    api.update = (_) async => throw const IdentityNetworkException();
    api.read = () async => throw const IdentityNetworkException();
    await expectLater(controller.save(value()), throwsA(isA<ProfileSaveUncertainException>()));
    expect(controller.profile?.version, 1);
    expect(controller.pendingSave, isTrue);
    expect(api.puts, 1);
    expect(api.gets, 2);
  });

  test('version conflict requires successful explicit reload before another save', () async {
    final api = Api();
    final controller = make(api, ProfileStore(Disk()));
    await controller.open();
    api.update = (_) async => throw const IdentityApiException(statusCode: 409,
      code: 'profile_version_conflict', message: 'Failure');
    await expectLater(controller.save(value()), throwsA(isA<IdentityApiException>()));
    expect(controller.needsReload, isTrue);
    expect(controller.pendingSave, isFalse);
    await expectLater(controller.save(value()), throwsStateError);
    api.read = () async => throw const IdentityNetworkException();
    await controller.refresh();
    expect(controller.needsReload, isTrue);
    api.read = () async => value(3);
    await controller.refresh();
    expect(controller.needsReload, isFalse);
    expect(controller.profile?.version, 3);
    expect(api.puts, 1);
  });

  test('late failing PUT after logout never verifies or restores another account', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final pending = Completer<Profile>();
    final started = Completer<void>();
    final api = Api()..update = (_) { started.complete(); return pending.future; };
    final controller = make(api, store);
    await controller.open();
    final saving = controller.save(value());
    final check = expectLater(saving, throwsA(isA<StaleProfileOperation>()));
    await started.future;
    await expectLater(controller.save(value()), throwsStateError);
    store.invalidate();
    await store.clear();
    await store.write('b', StoredProfile(profile: value(9), pendingSave: false), store.generation);
    pending.completeError(const IdentityNetworkException());
    await check;
    expect(controller.profile, isNull);
    expect(controller.error, isNull);
    expect(api.gets, 1);
    expect(api.puts, 1);
    expect((await store.read('b', store.generation))?.profile?.version, 9);
  });

  test('late disk write is followed by logout deletion', () async {
    final disk = Disk();
    final store = ProfileStore(disk);
    final entered = Completer<void>();
    final release = Completer<void>();
    disk.beforeWrite = () async { entered.complete(); await release.future; };
    final writing = store.write('a', StoredProfile(profile: value(), pendingSave: false), 0);
    final check = expectLater(writing, throwsA(isA<StaleProfileOperation>()));
    await entered.future;
    store.invalidate();
    final clearing = store.clear();
    release.complete();
    await check;
    await clearing;
    expect(disk.values, isEmpty);
  });
}

ProfileController make(Api api, ProfileStore store) =>
  ProfileController(api, store, () async => 'a');

class Api implements ProfileRepository {
  int gets = 0;
  int puts = 0;
  Future<Profile> Function() read = () async => value();
  Future<Profile> Function(Profile) update = (_) async => value(2);
  @override
  Future<Profile> getOwnProfile() { gets++; return read(); }
  @override
  Future<Profile> updateOwnProfile(Profile profile) { puts++; return update(profile); }
}

class Disk implements SecureValueStore {
  final values = <String, String>{};
  bool failWrite = false;
  bool failRead = false;
  bool failProfileDelete = false;
  Future<void> Function()? beforeWrite;
  @override
  Future<String?> read(String key) async {
    if (failRead) throw StateError('read');
    return values[key];
  }
  @override
  Future<void> write(String key, String value) async {
    await beforeWrite?.call();
    if (failWrite) throw StateError('write');
    values[key] = value;
  }
  @override
  Future<void> delete(String key) async {
    if (failProfileDelete && key == 'own_profile_v1') throw StateError('delete');
    values.remove(key);
  }
}

class AuthApi implements AuthApiClient {
  @override
  Future<Map<String, dynamic>> post(String path, {Object? data,
    Map<String, String>? headers}) async => {};
  @override
  Future<void> postWithoutResponse(String path, {Object? data,
    Map<String, String>? headers}) async {}
}
