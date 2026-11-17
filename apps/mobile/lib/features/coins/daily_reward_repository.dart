import '../../core/network/identity_api_client.dart';
import '../auth/auth_repository.dart';
import 'model/daily_reward_claim.dart';
import 'model/daily_reward_status.dart';

abstract interface class DailyRewardRepository {
  Future<DailyRewardStatus> getStatus();

  Future<DailyRewardClaim> claim(String idempotencyKey);
}

class HttpDailyRewardRepository implements DailyRewardRepository {
  HttpDailyRewardRepository(this._api, this._auth);

  static const _statusPath = '/api/v1/coins/daily-reward/status';
  static const _claimPath = '/api/v1/coins/daily-reward/claims';

  final IdentityApiClient _api;
  final AuthRepository _auth;

  @override
  Future<DailyRewardStatus> getStatus() => _auth.authenticatedRequest(
    (token) async => DailyRewardStatus.fromJson(
      await _api.get(
        _statusPath,
        headers: {'Authorization': 'Bearer $token'},
      ),
    ),
  );

  @override
  Future<DailyRewardClaim> claim(String idempotencyKey) =>
      _auth.authenticatedRequest(
        (token) async => DailyRewardClaim.fromJson(
          await _api.post(
            _claimPath,
            headers: {
              'Authorization': 'Bearer $token',
              'Idempotency-Key': idempotencyKey,
            },
          ),
        ),
      );
}
