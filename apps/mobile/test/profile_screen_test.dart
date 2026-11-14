import 'dart:async';

import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/app_shell/app_shell_screen.dart';
import 'package:fitness_app/features/auth/auth_controller.dart';
import 'package:fitness_app/features/auth/auth_repository.dart';
import 'package:fitness_app/features/profile/profile.dart';
import 'package:fitness_app/features/profile/profile_controller.dart';
import 'package:fitness_app/features/profile/profile_repository.dart';
import 'package:fitness_app/features/profile/profile_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

Profile profile({bool empty = false, int version = 987}) => Profile(
  nickname: 'Nikita', firstName: empty ? null : 'Nikita',
  lastName: empty ? null : 'Example', bio: empty ? null : 'Hello',
  dateOfBirth: empty ? null : '2000-02-29', timezone: 'Europe/Moscow',
  visibility: ProfileVisibility.public, version: version,
);

void main() {
  setUp(() => WidgetController.hitTestWarningShouldBeFatal = true);
  tearDown(() => WidgetController.hitTestWarningShouldBeFatal = false);
  Future<ProfileController> open(WidgetTester tester, Api api,
      {TestAuth? auth}) async {
    final store = ProfileStore(Disk());
    final controller = ProfileController(api, store, () async => 'account');
    addTearDown(controller.dispose);
    addTearDown(store.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        profileControllerProvider.overrideWithValue(controller),
        authControllerProvider.overrideWith(() => auth ?? TestAuth()),
      ],
      child: const MaterialApp(home: AppShellScreen()),
    ));
    await tester.pumpAndSettle();
    return controller;
  }

  Future<void> selectProfile(WidgetTester tester) async {
    await tester.tap(find.descendant(of: find.byType(NavigationBar),
      matching: find.text('Profile')));
    await tester.pumpAndSettle();
  }

  testWidgets('profile is lazy; other destinations disabled; selecting again uses cache',
    (tester) async {
      final api = Api();
      await open(tester, api);
      expect(find.text('You are signed in'), findsOneWidget);
      expect(api.gets, 0);
      final destinations = tester.widgetList<NavigationDestination>(
        find.byType(NavigationDestination)).toList();
      expect(destinations.map((d) => d.enabled), [false, false, true]);
      await tester.tap(find.text('Challenges'));
      await tester.pumpAndSettle();
      expect(find.text('You are signed in'), findsOneWidget);
      await selectProfile(tester);
      expect(api.gets, 1);
      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 2);
      await selectProfile(tester);
      expect(api.gets, 1);
    });

  testWidgets('all profile fields except version are shown', (tester) async {
    await open(tester, Api());
    await selectProfile(tester);
    for (final label in ['First name', 'Last name', 'Bio', 'Date of birth',
      'Timezone', 'Visibility']) {
      expect(find.text(label), findsOneWidget);
    }
    for (final text in ['Example', 'Hello', '2000-02-29', 'Europe/Moscow', 'Public']) {
      expect(find.text(text), findsOneWidget);
    }
    expect(find.text('987'), findsNothing);
    expect(find.text('Version'), findsNothing);
    expect(find.text('Email'), findsNothing);
    expect(find.byTooltip('Sign out'), findsOneWidget);
  });

  testWidgets('nullable fields show Not set and a completion hint', (tester) async {
    await open(tester, Api()..read = () async => profile(empty: true));
    await selectProfile(tester);
    expect(find.text('Not set'), findsNWidgets(4));
    expect(find.text('Tell us about yourself by completing your profile.'), findsOneWidget);
  });

  testWidgets('loading keeps exit available and Cancel does not sign out', (tester) async {
    final pending = Completer<Profile>();
    final auth = TestAuth();
    await open(tester, Api()..read = () => pending.future, auth: auth);
    await tester.tap(find.text('Profile'));
    await tester.pump();
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Sign out from this device'), findsOneWidget);
    expect(find.text('Sign out from all devices'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Sign out from this device'), findsNothing);
    expect(auth.current, 0);
    expect(auth.all, 0);
    pending.complete(profile());
    await tester.pumpAndSettle();
  });

  testWidgets('failed load has Retry and exit; retry loads profile', (tester) async {
    final api = Api()..read = () async => throw const IdentityNetworkException();
    await open(tester, api);
    await selectProfile(tester);
    expect(find.text('Could not load profile.'), findsOneWidget);
    expect(find.byTooltip('Sign out'), findsOneWidget);
    api.read = () async => profile();
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();
    expect(api.gets, 2);
    expect(find.text('Could not load profile.'), findsNothing);
    expect(find.text('Example'), findsOneWidget);
  });

  testWidgets('pull to refresh sends GET and preserves values on failure', (tester) async {
    final api = Api();
    await open(tester, api);
    await selectProfile(tester);
    api.read = () async => throw const IdentityNetworkException();
    await tester.drag(find.byType(ListView), const Offset(0, 400));
    await tester.pumpAndSettle();
    expect(api.gets, 2);
    expect(find.text('Example'), findsOneWidget);
    expect(find.text('Could not update profile. Pull down to try again.'), findsOneWidget);
  });

  testWidgets('profile exit supports current, all with confirmation, and cancel', (tester) async {
    final auth = TestAuth();
    await open(tester, Api(), auth: auth);
    await selectProfile(tester);
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign out from all devices'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(auth.all, 0);
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign out from all devices'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Sign out from all devices'));
    await tester.pumpAndSettle();
    expect(auth.all, 1);
    await tester.tap(find.byTooltip('Sign out'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign out from this device'));
    await tester.pumpAndSettle();
    expect(auth.current, 1);
  });
}

class Api implements ProfileRepository {
  int gets = 0;
  Future<Profile> Function() read = () async => profile();
  @override
  Future<Profile> getOwnProfile() { gets++; return read(); }
  @override
  Future<Profile> updateOwnProfile(Profile profile) => throw UnimplementedError();
}

class Disk implements SecureValueStore {
  final values = <String, String>{};
  @override
  Future<void> delete(String key) async { values.remove(key); }
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async { values[key] = value; }
}

class TestAuth extends AuthController {
  int current = 0;
  int all = 0;
  @override
  Future<AuthStatus> build() async => AuthStatus.authenticated;
  @override
  Future<LogoutResult> logout() async {
    current++;
    return const LogoutResult(remoteSucceeded: true, localTokensCleared: true);
  }
  @override
  Future<LogoutResult> logoutAll() async {
    all++;
    return const LogoutResult(remoteSucceeded: true, localTokensCleared: true);
  }
}
