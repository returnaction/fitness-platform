import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/identity_api_client.dart';
import '../auth/auth_controller.dart';
import '../auth/auth_repository.dart';
import 'wallet_balance.dart';

final walletRepositoryProvider = Provider<WalletRepository>(
  (ref) => WalletApiRepository(
    ref.watch(identityApiClientProvider),
    ref.watch(authRepositoryProvider),
  ),
);

final walletProvider = FutureProvider.autoDispose<WalletBalance>(
  (ref) => ref.watch(walletRepositoryProvider).getOwnWalletBalance(),
);

abstract interface class WalletRepository {
  Future<WalletBalance> getOwnWalletBalance();
}

class WalletApiRepository implements WalletRepository {
  WalletApiRepository(this._api, this._auth);

  static const _path = '/api/v1/coins/me';
  final IdentityApiClient _api;
  final AuthRepository _auth;

  @override
  Future<WalletBalance> getOwnWalletBalance() => _auth.authenticatedRequest(
    (token) async => WalletBalance.fromJson(await _api.get(
      _path,
      headers: {'Authorization': 'Bearer $token'},
    )),
  );
}
