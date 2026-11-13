import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final status in [200, 502, 503]) {
    test('HTML response $status is safe and is not retried', () async {
      final adapter = _Adapter()
        ..status = status
        ..rawBody = '<html>unavailable</html>'
        ..contentType = 'text/html';
      final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))
        ..httpClientAdapter = adapter;
      addTearDown(dio.close);
      await expectLater(
        IdentityApiClient(dio).post('/api/v1/auth/refresh'),
        status == 200
            ? throwsFormatException
            : throwsA(
                isA<IdentityApiException>()
                    .having((error) => error.statusCode, 'status', status)
                    .having((error) => error.code, 'code', isNull),
              ),
      );
      expect(adapter.requests, hasLength(1));
    });
  }
  test('malformed JSON refresh cannot reuse stored tokens', () async {
    final adapter = _Adapter()..rawBody = '{broken';
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))
      ..httpClientAdapter = adapter;
    addTearDown(dio.close);
    final storage = TokenStorage(_Store());
    await storage.write(
      const SessionTokens(accessToken: 'a', refreshToken: 'r'),
    );
    final repository = AuthRepository(
      IdentityApiClient(dio),
      storage,
      SessionOperationCoordinator(),
    );
    expect(await repository.restore(), SessionRestoreResult.guest);
    expect(await storage.read(), isNull);
    expect(await repository.restore(), SessionRestoreResult.guest);
    expect(adapter.requests, hasLength(1));
  });

  const pair = SessionTokens(accessToken: 'access', refreshToken: 'refresh');
  for (final operation in [
    'register',
    'login',
    'refresh',
    'logout',
    'logout-all',
  ]) {
    test(
      '$operation uses the sole Gateway URL and contract headers/body',
      () async {
        final adapter = _Adapter();
        final dio = Dio(
          BaseOptions(
            baseUrl: 'http://127.0.0.1:8088',
            headers: {'Accept': 'application/json'},
          ),
        )..httpClientAdapter = adapter;
        addTearDown(dio.close);
        adapter.body = operation == 'register'
            ? {'session': pair.toJson()}
            : pair.toJson();
        adapter.status = operation == 'register'
            ? 201
            : operation.startsWith('logout')
            ? 204
            : 200;
        final storage = TokenStorage(_Store());
        if (operation != 'register' && operation != 'login') {
          await storage.write(pair);
        }
        final repository = AuthRepository(
          IdentityApiClient(dio),
          storage,
          SessionOperationCoordinator(),
        );
        switch (operation) {
          case 'register':
            await repository.register(
              email: 'test@example.com',
              password: 'password',
              idempotencyKey: 'c0f01be9-363b-42d0-a425-e62044c5598b',
            );
          case 'login':
            await repository.login(
              email: 'test@example.com',
              password: 'password',
            );
          case 'refresh':
            await repository.restore();
          case 'logout':
            await repository.logout();
          case 'logout-all':
            await repository.logoutAll();
        }
        expect(adapter.requests, hasLength(1));
        final request = adapter.requests.single;
        expect(
          request.uri.toString(),
          'http://127.0.0.1:8088/api/v1/auth/$operation',
        );
        expect(request.method, 'POST');
        expect(request.headers['Accept'], 'application/json');
        expect(
          request.headers['Authorization'],
          operation == 'logout-all' ? 'Bearer access' : isNull,
        );
        expect(
          request.headers['Idempotency-Key'],
          operation == 'register'
              ? 'c0f01be9-363b-42d0-a425-e62044c5598b'
              : isNull,
        );
        if (operation == 'register' || operation == 'login') {
          expect(request.data, {
            'email': 'test@example.com',
            'password': 'password',
          });
        } else if (operation == 'logout-all') {
          expect(request.data, isNull);
        } else {
          expect(request.data, {'refreshToken': 'refresh'});
        }
      },
    );
  }

  for (final status in [413, 429, 502, 503, 504]) {
    test('Gateway $status is surfaced without retry', () async {
      final adapter = _Adapter()
        ..status = status
        ..body = {
          'error': {'code': 'rate_limited', 'message': 'Try again later.'},
        };
      final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))
        ..httpClientAdapter = adapter;
      addTearDown(dio.close);
      await expectLater(
        IdentityApiClient(dio).post('/api/v1/auth/refresh'),
        throwsA(
          isA<IdentityApiException>()
              .having((error) => error.statusCode, 'status', status)
              .having((error) => error.retryAfterSeconds, 'retry delay', 60),
        ),
      );
      expect(adapter.requests, hasLength(1));
    });
  }

  test('unexpected error field types produce a safe exception', () async {
    final adapter = _Adapter()
      ..status = 500
      ..body = {
        'error': {'code': 42, 'message': []},
      };
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))
      ..httpClientAdapter = adapter;
    addTearDown(dio.close);
    await expectLater(
      IdentityApiClient(dio).post('/api/v1/auth/login'),
      throwsA(
        isA<IdentityApiException>()
            .having((error) => error.code, 'code', isNull)
            .having(
              (error) => error.message,
              'message',
              'Request could not be completed.',
            ),
      ),
    );
  });
}

class _Adapter implements HttpClientAdapter {
  int status = 200;
  String? rawBody;
  String contentType = 'application/json';
  Object body = {};
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      rawBody ?? (status == 204 ? '' : jsonEncode(body)),
      status,
      headers: {
        'content-type': [contentType],
        'retry-after': ['60'],
        'cache-control': ['no-store'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

class _Store implements SecureValueStore {
  String? value;
  @override
  Future<void> delete(String key) async {
    value = null;
  }

  @override
  Future<String?> read(String key) async => value;
  @override
  Future<void> write(String key, String value) async {
    this.value = value;
  }
}
