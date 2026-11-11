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
  int deletes = 0;
  @override
  Future<String?> read(String key) async {
    if (failRead) throw StateError('unavailable');
    return null;
  }

  @override
  Future<void> write(String key, String value) async {}
  @override
  Future<void> delete(String key) async {
    deletes++;
  }
}

class _Api implements AuthApiClient {
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
    throw IdentityApiException(statusCode: 503, code: code, message: 'Failure');
  }

  @override
  Future<void> postWithoutResponse(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {}
}
