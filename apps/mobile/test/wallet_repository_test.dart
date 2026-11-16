import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:fitness_app/features/home/wallet_repository.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('wallet read uses Gateway, Bearer and the owner route', () async {
    final setup = await Setup.create((request) async {
      expect(request.uri.origin, 'http://127.0.0.1:8088');
      expect(request.path, '/api/v1/coins/me');
      expect(request.uri.query, isEmpty);
      expect(request.method, 'GET');
      expect(request.data, isNull);
      expect(request.headers['Authorization'], 'Bearer access');
      return response({'availableBalance': 120, 'lockedBalance': 20});
    });

    final wallet = await setup.repository.getOwnWalletBalance();

    expect(wallet.availableBalance, 120);
    expect(wallet.lockedBalance, 20);
    expect(setup.adapter.requests, hasLength(1));
  });

  test('wallet read refreshes once after invalid access token', () async {
    var reads = 0;
    final setup = await Setup.create((request) async {
      if (request.path == '/api/v1/auth/refresh') {
        expect(request.data, {'refreshToken': 'refresh'});
        return response({'accessToken': 'new-access', 'refreshToken': 'new-refresh'});
      }
      if (++reads == 1) return failure(401, 'invalid_access_token');
      expect(request.headers['Authorization'], 'Bearer new-access');
      return response({'availableBalance': 8, 'lockedBalance': 4});
    });

    final wallet = await setup.repository.getOwnWalletBalance();

    expect(wallet.availableBalance, 8);
    expect(setup.adapter.requests, hasLength(3));
  });
}

ResponseBody response(Object body, [int status = 200]) => ResponseBody.fromString(
  jsonEncode(body),
  status,
  headers: {'content-type': ['application/json']},
);

ResponseBody failure(int status, String code) => response({
  'error': {
    'code': code,
    'message': 'Failure',
    'details': [],
    'requestId': 'request-id',
  },
}, status);

class Setup {
  Setup(this.adapter, this.repository);

  final Adapter adapter;
  final WalletApiRepository repository;

  static Future<Setup> create(
    Future<ResponseBody> Function(RequestOptions) handler,
  ) async {
    final adapter = Adapter(handler);
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))
      ..httpClientAdapter = adapter;
    addTearDown(dio.close);
    final storage = TokenStorage(Store());
    await storage.write(
      const SessionTokens(accessToken: 'access', refreshToken: 'refresh'),
    );
    final api = IdentityApiClient(dio);
    final auth = AuthRepository(api, storage, SessionOperationCoordinator());
    return Setup(adapter, WalletApiRepository(api, auth));
  }
}

class Adapter implements HttpClientAdapter {
  Adapter(this.handler);

  final Future<ResponseBody> Function(RequestOptions) handler;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancelFuture,
  ) {
    requests.add(options);
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

class Store implements SecureValueStore {
  final values = <String, String>{};

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
