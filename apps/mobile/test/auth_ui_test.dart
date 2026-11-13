import 'dart:convert';

import 'package:fitness_app/app/fitness_app.dart';
import 'package:fitness_app/core/network/identity_api_client.dart';
import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/auth/auth_controller.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Future<void> openApp(WidgetTester tester, _Api api, _Store store) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(
            AuthRepository(
              api,
              TokenStorage(store),
              SessionOperationCoordinator(),
            ),
          ),
        ],
        child: const FitnessApp(),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('failed login allows back to Welcome', (tester) async {
    await openApp(tester, _Api(), _Store());
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextFormField).first,
      'test@example.com',
    );
    await tester.enterText(find.byType(TextFormField).last, 'test-password');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Email or password is incorrect.'), findsOneWidget);
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.text('Fitness App'), findsOneWidget);
    expect(find.text('Cannot connect to Fitness App.'), findsNothing);
  });

  Future<void> openLogoutAll(WidgetTester tester) async {
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign out from all devices'));
    await tester.pumpAndSettle();
    expect(find.text('Sign out from all devices?'), findsOneWidget);
  }

  testWidgets(
    'logout-all cancellation preserves session; confirmation clears it',
    (tester) async {
      final api = _Api()..succeed = true;
      final store = _Store()
        ..value = jsonEncode({'accessToken': 'a', 'refreshToken': 'r'});
      await openApp(tester, api, store);
      await openLogoutAll(tester);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(find.text('You are signed in'), findsOneWidget);
      expect(api.logoutCalls, 0);
      expect(store.value, isNotNull);
      await openLogoutAll(tester);
      await tester.tap(
        find.widgetWithText(FilledButton, 'Sign out from all devices'),
      );
      await tester.pumpAndSettle();
      expect(api.logoutCalls, 1);
      expect(api.logoutPath, '/api/v1/auth/logout-all');
      expect(store.value, isNull);
      expect(find.text('Fitness App'), findsOneWidget);
    },
  );

  for (final localLogout in [false, true]) {
    testWidgets(
      '503 keeps session then ${localLogout ? 'local logout' : 'retry'} works',
      (tester) async {
        final api = _Api()..code = 'identity_unavailable';
        final store = _Store()
          ..value = jsonEncode({'accessToken': 'a', 'refreshToken': 'r'});
        final original = store.value;
        await openApp(tester, api, store);
        expect(
          find.text('Service is temporarily unavailable.'),
          findsOneWidget,
        );
        expect(store.value, original);
        expect(api.calls, 1);
        if (localLogout) {
          await tester.tap(find.text('Log out on this device'));
          await tester.pumpAndSettle();
          expect(store.value, isNull);
          expect(api.calls, 1);
          expect(api.logoutCalls, 0);
          expect(find.text('Fitness App'), findsOneWidget);
        } else {
          api.succeed = true;
          await tester.tap(find.text('Try again'));
          await tester.pumpAndSettle();
          expect(api.calls, 2);
          expect(find.text('You are signed in'), findsOneWidget);
        }
      },
    );
  }

  testWidgets('failed token write at login shows safe error and stays guest', (
    tester,
  ) async {
    final store = _Store()..failWrite = true;
    await openApp(tester, _Api()..succeed = true, store);
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextFormField).first,
      'test@example.com',
    );
    await tester.enterText(find.byType(TextFormField).last, 'test-password');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    expect(
      find.text('Could not save your session securely. Please try again.'),
      findsOneWidget,
    );
    expect(find.text('You are signed in'), findsNothing);
    expect(store.value, isNull);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed logout deletion retries local cleanup without refresh', (
    tester,
  ) async {
    final api = _Api()..succeed = true;
    final store = _Store()
      ..value = jsonEncode({'accessToken': 'a', 'refreshToken': 'r'});
    await openApp(tester, api, store);
    store.failDelete = true;
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign out from this device'));
    await tester.pumpAndSettle();
    expect(find.text('Secure storage is unavailable.'), findsOneWidget);
    expect(find.text('Local sign out could not be confirmed.'), findsOneWidget);
    expect(store.value, isNotNull);
    store.failDelete = false;
    await tester.tap(find.text('Clear local session'));
    await tester.pumpAndSettle();
    expect(store.value, isNull);
    expect(api.calls, 1);
    expect(api.logoutCalls, 1);
    expect(find.text('Fitness App'), findsOneWidget);
  });

  testWidgets('remote logout-all failure warns after leaving account screen', (
    tester,
  ) async {
    final api = _Api()
      ..succeed = true
      ..failLogout = true;
    final store = _Store()
      ..value = jsonEncode({'accessToken': 'a', 'refreshToken': 'r'});
    await openApp(tester, api, store);
    await openLogoutAll(tester);
    await tester.tap(
      find.widgetWithText(FilledButton, 'Sign out from all devices'),
    );
    await tester.pumpAndSettle();
    expect(store.value, isNull);
    expect(find.text('Fitness App'), findsOneWidget);
    expect(
      find.text(
        'You have been signed out on this device. Other devices may still be signed in.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('registration retry keeps key until credentials change', (
    tester,
  ) async {
    final api = _Api()..code = 'registration_unavailable';
    await openApp(tester, api, _Store());
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextFormField).first,
      'test@example.com',
    );
    await tester.enterText(find.byType(TextFormField).last, 'test-password');
    expect(
      tester.widget<TextField>(find.byType(TextField).last).obscureText,
      isTrue,
    );
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pump();
    expect(
      tester.widget<TextField>(find.byType(TextField).last).obscureText,
      isFalse,
    );
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pump();
    expect(
      tester.widget<TextField>(find.byType(TextField).last).obscureText,
      isTrue,
    );
    await tester.tap(find.widgetWithText(FilledButton, 'Create account'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Try again'));
    await tester.pumpAndSettle();
    expect(api.keys[0], api.keys[1]);
    await tester.enterText(find.byType(TextFormField).last, 'changed-password');
    await tester.tap(find.widgetWithText(FilledButton, 'Try again'));
    await tester.pumpAndSettle();
    expect(api.keys[2], isNot(api.keys[1]));
  });

  testWidgets('storage failure offers local cleanup without refresh', (
    tester,
  ) async {
    final api = _Api();
    final store = _Store()..failRead = true;
    await openApp(tester, api, store);
    expect(find.text('Secure storage is unavailable.'), findsOneWidget);
    await tester.tap(find.text('Clear local session'));
    await tester.pumpAndSettle();
    expect(find.text('Fitness App'), findsOneWidget);
    expect(store.deletes, 1);
    expect(api.calls, 0);
  });
}

class _Store implements SecureValueStore {
  bool failRead = false;
  bool failWrite = false;
  bool failDelete = false;
  String? value;
  int deletes = 0;
  @override
  Future<String?> read(String key) async {
    if (failRead) throw StateError('unavailable');
    return value;
  }

  @override
  Future<void> write(String key, String value) async {
    if (failWrite) throw StateError('unavailable');
    this.value = value;
  }

  @override
  Future<void> delete(String key) async {
    deletes++;
    if (failDelete) throw StateError('unavailable');
    value = null;
  }
}

class _Api implements AuthApiClient {
  bool succeed = false;
  bool failLogout = false;
  int logoutCalls = 0;
  String? logoutPath;
  String code = 'invalid_credentials';
  final keys = <String?>[];
  int calls = 0;
  @override
  Future<Map<String, dynamic>> post(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {
    calls++;
    keys.add(headers?['Idempotency-Key']);
    if (succeed) return {'accessToken': 'new-a', 'refreshToken': 'new-r'};
    throw IdentityApiException(statusCode: 503, code: code, message: 'Failure');
  }

  @override
  Future<void> postWithoutResponse(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {
    logoutCalls++;
    logoutPath = path;
    if (failLogout) throw const IdentityNetworkException();
  }
}
