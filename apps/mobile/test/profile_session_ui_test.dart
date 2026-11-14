import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:fitness_app/app/fitness_app.dart';
import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_controller.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/model/session_tokens.dart';
import 'package:fitness_app/features/profile/edit_profile_screen.dart';
import 'package:fitness_app/features/profile/profile_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  setUp(() => WidgetController.hitTestWarningShouldBeFatal = true);
  tearDown(() => WidgetController.hitTestWarningShouldBeFatal = false);
  Future<Setup> open(WidgetTester tester) async {
    final setup = Setup();
    await setup.tokens.write(SessionTokens(accessToken: setup.api.token(), refreshToken: 'r0'));
    final dio = Dio(BaseOptions(baseUrl: 'http://127.0.0.1:8088'))..httpClientAdapter = setup.api;
    setup.container = ProviderContainer(overrides: [
      identityApiClientProvider.overrideWithValue(IdentityApiClient(dio)),
      tokenStorageProvider.overrideWithValue(setup.tokens),
      profileStoreProvider.overrideWithValue(setup.profileStore),
    ]);
    addTearDown(() { setup.container.dispose(); setup.profileStore.dispose(); dio.close(); });
    await tester.pumpWidget(UncontrolledProviderScope(
      container: setup.container, child: const FitnessApp()));
    await tester.pumpAndSettle();
    expect(find.text('You are signed in'), findsOneWidget);
    return setup;
  }

  Future<void> edit(WidgetTester tester) async {
    await tester.tap(find.text('Profile'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Edit profile'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Edit profile'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('nickname')), 'Changed');
  }

  for (final atSave in [false, true]) {
    testWidgets('expired session during ${atSave ? 'PUT' : 'GET'} clears profile and routes to entry',
      (tester) async {
        final setup = await open(tester);
        if (atSave) await edit(tester);
        setup.api.rejectResources = true;
        setup.api.refreshStatus = 401;
        if (atSave) {
          await tester.tap(find.widgetWithText(FilledButton, 'Save'));
        } else {
          await tester.tap(find.text('Profile'));
        }
        await tester.pumpAndSettle();
        expect(find.text('Fitness App'), findsOneWidget);
        expect(find.byType(EditProfileScreen), findsNothing);
        expect(find.text('Profile updated'), findsNothing);
        expect(await setup.tokens.read(), isNull);
        expect(setup.disk.values, isEmpty);
        expect(setup.api.refreshes, 2);
        expect(setup.api.puts, atSave ? 1 : 0);
        expect(tester.takeException(), isNull);
      });
  }

  testWidgets('temporary refresh outage preserves draft and session', (tester) async {
    final setup = await open(tester);
    await edit(tester);
    setup.api.rejectResources = true;
    setup.api.refreshStatus = 503;
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();
    expect(find.byType(EditProfileScreen), findsOneWidget);
    expect(tester.widget<TextFormField>(find.byKey(const ValueKey('nickname')))
      .controller!.text, 'Changed');
    expect(await setup.tokens.read(), isNotNull);
    expect(setup.container.read(authControllerProvider).requireValue, AuthStatus.authenticated);
    expect(setup.api.puts, 1);
  });

  testWidgets('session storage failure hides editor and offers local cleanup', (tester) async {
    final setup = await open(tester);
    await edit(tester);
    setup.disk.failTokenRead = true;
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();
    expect(find.text('Secure storage is unavailable.'), findsOneWidget);
    expect(find.byType(EditProfileScreen), findsNothing);
    expect(setup.api.puts, 0);
    setup.disk.failTokenRead = false;
    await tester.tap(find.text('Clear local session'));
    await tester.pumpAndSettle();
    expect(setup.disk.values, isEmpty);
    expect(find.text('Fitness App'), findsOneWidget);
  });

  testWidgets('logout and new login ignore the previous pending PUT and stale session event', (tester) async {
    final setup = await open(tester);
    await edit(tester);
    setup.api.pendingPut = Completer<ResponseBody>();
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(setup.api.puts, 1);
    final oldGeneration = setup.container.read(authRepositoryProvider).sessionGeneration;
    await tester.tap(find.byTooltip('Back'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.text('Sign out from this device'));
    await tester.pumpAndSettle();
    expect(find.text('Fitness App'), findsOneWidget);
    setup.api.account = 'AccountB';
    final login = setup.container.read(authControllerProvider.notifier)
      .login(email: 'b@example.test', password: 'test-password');
    await tester.pumpAndSettle();
    await login;
    setup.container.read(authControllerProvider.notifier)
      .handleRequestSessionFailure(SessionRestoreResult.guest, oldGeneration);
    setup.api.pendingPut!.complete(setup.api.failure(401, 'invalid_access_token'));
    await tester.pumpAndSettle();
    expect(find.text('You are signed in'), findsOneWidget);
    expect(find.text('Profile updated'), findsNothing);
    expect(find.textContaining('Could not confirm profile update'), findsNothing);
    await tester.tap(find.text('Profile'));
    await tester.pumpAndSettle();
    expect(find.text('AccountB'), findsOneWidget);
    expect(find.text('AccountA'), findsNothing);
    expect(setup.api.puts, 1);
    expect(setup.api.refreshes, 1);
    expect(tester.takeException(), isNull);
  });
}

class Setup {
  final disk = Disk();
  final api = Adapter();
  late final tokens = TokenStorage(disk);
  late final profileStore = ProfileStore(disk);
  late ProviderContainer container;
}

class Adapter implements HttpClientAdapter {
  String account = 'AccountA';
  int refreshes = 0;
  int refreshStatus = 200;
  int puts = 0;
  bool rejectResources = false;
  Completer<ResponseBody>? pendingPut;
  String token() => 'header.${base64Url.encode(utf8.encode(jsonEncode({'sub': account})))}.$refreshes';
  ResponseBody response(Object body, [int status = 200]) =>
      ResponseBody.fromString(jsonEncode(body), status,
        headers: {'content-type': ['application/json']});
  ResponseBody failure(int status, String code) => response({
    'error': {'code': code, 'message': 'Failure', 'details': [], 'requestId': 'id'},
  }, status);
  @override
  Future<ResponseBody> fetch(RequestOptions request, Stream<Uint8List>? stream,
    Future<void>? cancelFuture) async {
    if (request.path.endsWith('/logout')) return response({}, 204);
    if (request.path.endsWith('/login')) {
      return response({'accessToken': token(), 'refreshToken': 'new-login'});
    }
    if (request.path.endsWith('/refresh')) {
      refreshes++;
      if (refreshStatus != 200) {
        return failure(refreshStatus,
          refreshStatus == 503 ? 'identity_unavailable' : 'invalid_refresh_token');
      }
      return response({'accessToken': token(), 'refreshToken': 'r$refreshes'});
    }
    if (request.method == 'PUT') {
      puts++;
      if (pendingPut != null) return pendingPut!.future;
    }
    if (rejectResources) return failure(401, 'invalid_access_token');
    return response({'nickname': account, 'firstName': null, 'lastName': null,
      'bio': null, 'dateOfBirth': null, 'timezone': 'UTC', 'visibility': 'PRIVATE', 'version': 1});
  }
  @override
  void close({bool force = false}) {}
}

class Disk implements SecureValueStore {
  final values = <String, String>{};
  bool failTokenRead = false;
  @override
  Future<void> delete(String key) async { values.remove(key); }
  @override
  Future<String?> read(String key) async {
    if (failTokenRead && key == 'auth_session_v1') throw StateError('storage');
    return values[key];
  }
  @override
  Future<void> write(String key, String value) async { values[key] = value; }
}
