import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/identity_api_client.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_repository.dart';
import 'profile.dart';

final profileRepositoryProvider = Provider<ProfileRepository>(
  (ref) => ProfileRepository(
    ref.watch(identityApiClientProvider),
    ref.watch(authRepositoryProvider),
  ),
);

class ProfileRepository {
  ProfileRepository(this._api, this._auth);

  static const _path = '/api/v1/accounts/me';
  final IdentityApiClient _api;
  final AuthRepository _auth;

  Future<Profile> getOwnProfile() => _auth.authenticatedRequest(
    (token) async => Profile.fromJson(await _api.get(
      _path, headers: {'Authorization': 'Bearer $token'},
    )),
  );

  Future<Profile> updateOwnProfile(Profile profile) => _auth.authenticatedRequest(
    (token) async => Profile.fromJson(await _api.put(
      _path,
      data: profile.toJson(),
      headers: {'Authorization': 'Bearer $token'},
    )),
  );
}
