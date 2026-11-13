import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/identity_api_error.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_repository.dart';
import '../auth/session_operation_coordinator.dart';
import 'profile.dart';
import 'profile_form_rules.dart';
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
  bool needsReload = false;
  int get sessionRevision => _store.generation;
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
      if (profile != null && fetched.version < profile!.version) {
        throw const ProfileSaveUncertainException();
      }
      await _store.write(account,
        StoredProfile(profile: fetched, pendingSave: false), generation);
      _check(generation);
      profile = fetched;
      pendingSave = false;
      needsReload = false;
    });
  }

  /// The pending marker is durable before PUT. No form draft is persisted.
  Future<void> save(Profile submitted) async {
    if (saving || pendingSave || needsReload) {
      throw StateError('Reload the profile before starting another save.');
    }
    final generation = _store.generation;
    _revision++;
    saving = true;
    error = null;
    notifyListeners();
    var sent = false;
    String? account;
    try {
      account = await _accountId();
      _check(generation);
      await _persist(account, profile, true, generation);
      _check(generation);
      sent = true;
      final saved = await _repository.updateOwnProfile(submitted);
      await _persist(account, saved, false, generation);
    } catch (failure) {
      _check(generation);
      error = failure;
      if (sent && account != null) {
        if (_isDefiniteRejection(failure)) {
          needsReload = failure is IdentityApiException &&
              failure.code == 'profile_version_conflict';
          try {
            await _persist(account, profile, false, generation);
          } catch (storageFailure) {
            _check(generation);
            error = storageFailure;
            needsReload = true;
          }
        } else if (_mayHaveCommitted(failure)) {
          if (await _checkUncertainSave(account, submitted, generation)) return;
          throw const ProfileSaveUncertainException();
        } else {
          needsReload = pendingSave;
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

  bool _isDefiniteRejection(Object failure) =>
      (failure is IdentityApiException &&
        const [400, 409, 415, 422, 429].contains(failure.statusCode)) ||
      (failure is SessionRequestException &&
        failure.result == SessionRestoreResult.temporarilyUnavailable);

  bool _mayHaveCommitted(Object failure) =>
      failure is IdentityNetworkException || failure is FormatException ||
      (failure is IdentityApiException &&
        (failure.statusCode == null || failure.statusCode! >= 500));

  Future<void> _persist(String account, Profile? value, bool pending, int generation) =>
      _commits.run(() async {
        _check(generation);
        await _store.write(account,
          StoredProfile(profile: value, pendingSave: pending), generation);
        _check(generation);
        profile = value;
        pendingSave = pending;
      });

  Future<bool> _checkUncertainSave(String account, Profile submitted, int generation) async {
    needsReload = true;
    try {
      final current = await _repository.getOwnProfile();
      _check(generation);
      if (profile != null && current.version < profile!.version) {
        throw const ProfileSaveUncertainException();
      }
      final confirmed = sameProfileValues(current, submitted);
      // A mismatching read does not authorize another PUT with a new version.
      await _persist(account, current, !confirmed, generation);
      if (confirmed) {
        needsReload = false;
        error = null;
        return true;
      }
    } catch (failure) {
      _check(generation);
      error = failure;
    }
    error = const ProfileSaveUncertainException();
    return false;
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
    needsReload = false;
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

class ProfileSaveUncertainException implements Exception {
  const ProfileSaveUncertainException();
}
