import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/identity_api_error.dart';
import '../auth/auth_controller.dart';
import '../auth/session_operation_coordinator.dart';
import 'profile.dart';
import 'profile_repository.dart';
import 'profile_store.dart';

final profileControllerProvider = Provider<ProfileController>((ref) {
  final controller = ProfileController(
    ref.watch(profileRepositoryProvider),
    ref.watch(profileStoreProvider),
    ref.watch(authRepositoryProvider).profileAccountId,
  );
  ref.onDispose(controller.dispose);
  return controller;
});

class ProfileController extends ChangeNotifier {
  ProfileController(this._repository, this._store, this._accountId) {
    _store.addListener(_reset);
  }

  final ProfileRepository _repository;
  final ProfileStore _store;
  final Future<String> Function() _accountId;
  final _commits = SessionOperationCoordinator();
  Profile? profile;
  Object? error;
  bool loading = false;
  bool saving = false;
  bool pendingSave = false;
  bool _disposed = false;
  int _revision = 0;
  Future<void>? _opening;

  Future<void> open() {
    if (saving) return Future.value();
    if (profile != null && !pendingSave) return Future.value();
    return _opening ??= _open();
  }

  Future<void> _open() async {
    final generation = _store.generation;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final account = await _accountId();
      _check(generation);
      final cached = await _store.read(account, generation);
      _check(generation);
      profile = cached?.profile;
      pendingSave = cached?.pendingSave ?? false;
      if (profile == null || pendingSave) {
        await _fetch(account, generation, _revision);
      }
    } catch (failure) {
      if (_active(generation)) error = failure;
    } finally {
      if (_active(generation)) {
        loading = false;
        _opening = null;
        notifyListeners();
      }
    }
  }

  Future<void> refresh() async {
    if (loading || saving) return;
    final generation = _store.generation;
    final revision = _revision;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final account = await _accountId();
      _check(generation);
      await _fetch(account, generation, revision);
    } catch (failure) {
      if (_active(generation)) error = failure;
    } finally {
      if (_active(generation)) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> _fetch(String account, int generation, int revision) async {
    final fetched = await _repository.getOwnProfile();
    await _commits.run(() async {
      _check(generation);
      if (revision != _revision) return;
      if (profile != null && fetched.version < profile!.version) return;
      await _store.write(account,
        StoredProfile(profile: fetched, pendingSave: false), generation);
      _check(generation);
      profile = fetched;
      pendingSave = false;
    });
  }

  /// The pending marker is durable before PUT. No form draft is persisted.
  Future<void> save(Profile submitted) async {
    if (saving) throw StateError('A profile save is already running.');
    final generation = _store.generation;
    _revision++;
    saving = true;
    error = null;
    notifyListeners();
    var sent = false;
    try {
      final account = await _accountId();
      _check(generation);
      await _commits.run(() async {
        _check(generation);
        await _store.write(account,
          StoredProfile(profile: profile, pendingSave: true), generation);
        _check(generation);
        pendingSave = true;
      });
      _check(generation);
      sent = true;
      final saved = await _repository.updateOwnProfile(submitted);
      await _commits.run(() async {
        _check(generation);
        await _store.write(account,
          StoredProfile(profile: saved, pendingSave: false), generation);
        _check(generation);
        profile = saved;
        pendingSave = false;
      });
    } catch (failure) {
      if (_active(generation)) {
        error = failure;
        // Definite client rejections did not commit; transport/5xx failures may have.
        if (sent && failure is IdentityApiException &&
            const [400, 409, 415, 422].contains(failure.statusCode)) {
          try {
            final account = await _accountId();
            await _commits.run(() async {
              _check(generation);
              await _store.write(account,
                StoredProfile(profile: profile, pendingSave: false), generation);
              _check(generation);
              pendingSave = false;
            });
          } catch (storageFailure) {
            if (_active(generation)) error = storageFailure;
          }
        }
      }
      rethrow;
    } finally {
      if (_active(generation)) {
        saving = false;
        notifyListeners();
      }
    }
  }

  bool _active(int generation) => !_disposed && generation == _store.generation;

  void _check(int generation) {
    if (!_active(generation)) throw const StaleProfileOperation();
  }

  void _reset() {
    _revision++;
    profile = null;
    error = null;
    pendingSave = false;
    loading = false;
    saving = false;
    _opening = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _store.removeListener(_reset);
    super.dispose();
  }
}
