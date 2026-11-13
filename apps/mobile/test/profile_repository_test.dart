import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:fitness_app/features/profile/profile.dart';
import 'package:fitness_app/features/profile/profile_repository.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> profileJson() => {
  'nickname': 'Nikita', 'firstName': null, 'lastName': null, 'bio': null,
  'dateOfBirth': '2000-02-29', 'timezone': 'Europe/Moscow',
  'visibility': 'PRIVATE', 'version': 1,
};

ResponseBody response(Object body, [int status = 200]) =>
    ResponseBody.fromString(jsonEncode(body), status,
      headers: {'content-type': ['application/json']});

ResponseBody failure(int status, String code) => response({
  'error': {'code': code, 'message': 'Failure', 'requestId': 'request-id',
    'details': [{'field': 'nickname', 'code': 'invalid_length'}]},
}, status);

void main() {
  test('strict profile parsing preserves nulls, date, enum and version', () {
    expect(Profile.fromJson(profileJson()).toJson(), profileJson());
    for (final field in profileJson().keys) {
      final missing = profileJson()..remove(field);
      expect(() => Profile.fromJson(missing), throwsFormatException);
    }
    for (final change in [
      {'version': 1.5}, {'version': '1'}, {'version': 0},
      {'visibility': 'public'}, {'firstName': 42},
      {'dateOfBirth': '2001-02-29'}, {'dateOfBirth': '2000-02-29T00:00:00Z'},
    ]) {
      expect(() => Profile.fromJson({...profileJson(), ...change}),
        throwsFormatException);
    }
    expect(Profile.fromJson({...profileJson(), 'dateOfBirth': null,
      'visibility': 'PUBLIC'}).visibility, ProfileVisibility.public);
  });

  for (final update in [false, true]) {
    test('${update ? 'PUT' : 'GET'} goes through Gateway with Bearer', () async {
      final setup = await Setup.create((request) async {
        expect(request.uri.origin, 'http://127.0.0.1:8088');
        expect(request.path, '/api/v1/accounts/me');
        expect(request.uri.query, isEmpty);
        expect(request.headers['Authorization'], 'Bearer access');
        expect(request.method, update ? 'PUT' : 'GET');
        if (update) {
          expect(request.data, profileJson());
          expect(request.contentType, 'application/json');
        } else {
          expect(request.data, isNull);
        }
        return response({...profileJson(), 'version': update ? 2 : 1});
      });
      final result = update
        ? await setup.profile.updateOwnProfile(Profile.fromJson(profileJson()))
        : await setup.profile.getOwnProfile();
      expect(result.version, update ? 2 : 1);
      expect(setup.adapter.requests, hasLength(1));
    });
  }

  for (final code in ['nickname_already_taken', 'profile_version_conflict',
    'validation_error', 'profile_unavailable']) {
    test('$code keeps details/requestId and does not retry', () async {
      final status = code == 'validation_error' ? 422
          : code == 'profile_unavailable' ? 503 : 409;
      final setup = await Setup.create((_) async => failure(status, code));
      await expectLater(setup.profile.updateOwnProfile(
        Profile.fromJson(profileJson())), throwsA(isA<IdentityApiException>()
          .having((e) => e.code, 'code', code)
          .having((e) => e.details.single.field, 'field', 'nickname')
          .having((e) => e.details.single.code, 'detail code', 'invalid_length')
          .having((e) => e.requestId, 'requestId', 'request-id')));
      expect(setup.adapter.requests, hasLength(1));
    });
  }

  test('timeout PUT is never retried or refreshed', () async {
    final setup = await Setup.create((request) async => throw DioException(
      requestOptions: request, type: DioExceptionType.receiveTimeout));
    await expectLater(setup.profile.updateOwnProfile(Profile.fromJson(profileJson())),
      throwsA(isA<IdentityNetworkException>()));
    expect(setup.adapter.requests, hasLength(1));
  });

  test('401 refreshes through existing auth and retries PUT once', () async {
    var puts = 0;
    final setup = await Setup.create((request) async {
      if (request.path == '/api/v1/auth/refresh') {
        expect(request.data, {'refreshToken': 'refresh'});
        return response({'accessToken': 'new-access', 'refreshToken': 'new-refresh'});
      }
      if (++puts == 1) return failure(401, 'invalid_access_token');
      expect(request.headers['Authorization'], 'Bearer new-access');
      return response({...profileJson(), 'version': 2});
    });
    expect((await setup.profile.updateOwnProfile(
      Profile.fromJson(profileJson()))).version, 2);
    expect(setup.adapter.requests, hasLength(3));
    expect((await setup.storage.read())?.refreshToken, 'new-refresh');
  });

  test('second 401 ends session without a refresh loop', () async {
    final setup = await Setup.create((request) async =>
      request.path == '/api/v1/auth/refresh'
        ? response({'accessToken': 'new-access', 'refreshToken': 'new-refresh'})
        : failure(401, 'invalid_access_token'));
    await expectLater(setup.profile.getOwnProfile(), throwsA(
      isA<SessionRequestException>().having((e) => e.result, 'result',
        SessionRestoreResult.guest)));
    expect(setup.adapter.requests, hasLength(3));
    expect(await setup.storage.read(), isNull);
  });

  test('concurrent 401 responses share one coordinated refresh', () async {
    final bothStarted = Completer<void>();
    var oldRequests = 0;
    var refreshes = 0;
    final setup = await Setup.create((request) async {
      if (request.path == '/api/v1/auth/refresh') {
        refreshes++;
        return response({'accessToken': 'new-access', 'refreshToken': 'new-refresh'});
      }
      if (request.headers['Authorization'] == 'Bearer access') {
        if (++oldRequests == 2) bothStarted.complete();
        await bothStarted.future;
        return failure(401, 'invalid_access_token');
      }
      return response(profileJson());
    });
    await Future.wait([setup.profile.getOwnProfile(), setup.profile.getOwnProfile()]);
    expect(refreshes, 1);
  });

  test('logout proceeds during resource request and drops its late response', () async {
    final started = Completer<void>();
    final pending = Completer<ResponseBody>();
    final setup = await Setup.create((request) async {
      if (request.path == '/api/v1/auth/logout') return response({});
      started.complete();
      return pending.future;
    });
    final request = setup.profile.getOwnProfile();
    final check = expectLater(request, throwsA(isA<SessionChangedException>()));
    await started.future;
    expect((await setup.auth.logout()).localTokensCleared, isTrue);
    pending.complete(response(profileJson()));
    await check;
    expect(await setup.storage.read(), isNull);
  });
}

class Setup {
  Setup(this.adapter, this.storage, this.auth, this.profile);
  final Adapter adapter;
  final TokenStorage storage;
  final AuthRepository auth;
  final ProfileRepository profile;

  static Future<Setup> create(Future<ResponseBody> Function(RequestOptions) handler) async {
    final adapter = Adapter(handler);
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))
      ..httpClientAdapter = adapter;
    addTearDown(dio.close);
    final api = IdentityApiClient(dio);
    final storage = TokenStorage(Store());
    await storage.write(const SessionTokens(accessToken: 'access', refreshToken: 'refresh'));
    final auth = AuthRepository(api, storage, SessionOperationCoordinator());
    return Setup(adapter, storage, auth, ProfileRepository(api, auth));
  }
}

class Adapter implements HttpClientAdapter {
  Adapter(this.handler);
  final Future<ResponseBody> Function(RequestOptions) handler;
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? stream,
    Future<void>? cancelFuture) {
    requests.add(options);
    return handler(options);
  }
  @override
  void close({bool force = false}) {}
}

class Store implements SecureValueStore {
  final values = <String, String>{};
  @override
  Future<void> delete(String key) async { values.remove(key); }
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async { values[key] = value; }
}

