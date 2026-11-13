import 'dart:async';

import 'package:fitness_app/core/network/identity_api_error.dart';
import 'package:fitness_app/core/security/token_storage.dart';
import 'package:fitness_app/features/profile/edit_profile_screen.dart';
import 'package:fitness_app/features/profile/profile.dart';
import 'package:fitness_app/features/profile/profile_controller.dart';
import 'package:fitness_app/features/profile/profile_repository.dart';
import 'package:fitness_app/features/profile/profile_screen.dart';
import 'package:fitness_app/features/profile/profile_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

Profile sample() => const Profile(nickname: 'Nikita', firstName: 'Nikita',
  lastName: 'Example', bio: 'Hello', dateOfBirth: '2000-02-29', timezone: 'UTC',
  visibility: ProfileVisibility.private, version: 7);

void main() {
  setUp(() => WidgetController.hitTestWarningShouldBeFatal = true);
  tearDown(() => WidgetController.hitTestWarningShouldBeFatal = false);

  Future<ProfileController> open(WidgetTester tester, Api api) async {
    final store = ProfileStore(Disk());
    final controller = ProfileController(api, store, () async => 'a');
    addTearDown(controller.dispose);
    addTearDown(store.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [profileControllerProvider.overrideWithValue(controller)],
      child: const MaterialApp(home: ProfileScreen()),
    ));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Edit profile'));
    await tester.tap(find.text('Edit profile'));
    await tester.pumpAndSettle();
    return controller;
  }

  Future<void> enter(WidgetTester tester, String field, String text) async {
    final finder = find.byKey(ValueKey(field));
    await tester.ensureVisible(finder);
    await tester.enterText(finder, text);
    await tester.pump();
  }

  Future<void> save(WidgetTester tester) async {
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();
  }

  testWidgets('unchanged normalized form closes with no PUT or extra GET', (tester) async {
    final api = Api();
    await open(tester, api);
    await enter(tester, 'firstName', 'Nikita   ');
    await save(tester);
    expect(api.puts, 0);
    expect(api.gets, 1);
    expect(find.byType(EditProfileScreen), findsNothing);
  });

  testWidgets('save sends normalized full profile and shows returned result', (tester) async {
    final api = Api();
    final controller = await open(tester, api);
    await enter(tester, 'nickname', ' NewNickname ');
    await enter(tester, 'firstName', '  ');
    await enter(tester, 'lastName', ' Newname ');
    await enter(tester, 'bio', ' New bio ');
    await tester.ensureVisible(find.byTooltip('Clear date of birth'));
    await tester.tap(find.byTooltip('Clear date of birth'));
    await tester.ensureVisible(find.text('Public'));
    await tester.tap(find.text('Public'));
    await save(tester);
    expect(api.puts, 1);
    expect(api.sent!.toJson(), {
      'nickname': 'NewNickname', 'firstName': null, 'lastName': 'Newname',
      'bio': 'New bio', 'dateOfBirth': null, 'timezone': 'UTC',
      'visibility': 'PUBLIC', 'version': 7,
    });
    expect(controller.profile?.version, 8);
    expect(find.byType(EditProfileScreen), findsNothing);
    expect(find.text('Profile updated'), findsOneWidget);
  });

  testWidgets('back discards draft without confirmation or PUT', (tester) async {
    final api = Api();
    await open(tester, api);
    await enter(tester, 'nickname', 'Changed');
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(api.puts, 0);
    await tester.ensureVisible(find.text('Edit profile'));
    await tester.tap(find.text('Edit profile'));
    await tester.pumpAndSettle();
    expect(tester.widget<TextFormField>(find.byKey(const ValueKey('nickname')))
      .controller!.text, 'Nikita');
  });

  testWidgets('invalid fields prevent PUT, occupied nickname keeps input', (tester) async {
    final api = Api();
    await open(tester, api);
    await enter(tester, 'nickname', 'a');
    await save(tester);
    expect(api.puts, 0);
    expect(find.textContaining('Use 3'), findsOneWidget);
    await enter(tester, 'nickname', 'TakenName');
    api.failure = const IdentityApiException(statusCode: 409,
      code: 'nickname_already_taken', message: 'Failure');
    await save(tester);
    expect(find.text('This nickname is already taken.'), findsOneWidget);
    expect(tester.widget<TextFormField>(find.byKey(const ValueKey('nickname')))
      .controller!.text, 'TakenName');
    expect(find.byType(EditProfileScreen), findsOneWidget);
  });

  testWidgets('server validation attaches errors to the matching field', (tester) async {
    final api = Api()..failure = const IdentityApiException(statusCode: 422,
      code: 'validation_error', message: 'Failure', details: [
        ApiFieldError(field: 'firstName', code: 'invalid_format'),
      ]);
    await open(tester, api);
    await enter(tester, 'firstName', 'Different');
    await save(tester);
    expect(find.text('Use letters, spaces, hyphens or apostrophes.'), findsOneWidget);
    expect(api.puts, 1);
  });

  testWidgets('timezone picker searches and applies selected ID', (tester) async {
    final api = Api();
    await open(tester, api);
    await tester.ensureVisible(find.text('Timezone'));
    await tester.tap(find.text('Timezone'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Search timezone'), 'Moscow');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Europe/Moscow'));
    await tester.pumpAndSettle();
    await save(tester);
    expect(api.sent?.timezone, 'Europe/Moscow');
  });

  testWidgets('date picker keeps calendar date without timezone conversion', (tester) async {
    final api = Api();
    await open(tester, api);
    await tester.ensureVisible(find.text('Date of birth'));
    await tester.tap(find.text('Date of birth'));
    await tester.pumpAndSettle();
    expect(find.byType(DatePickerDialog), findsOneWidget);
    await tester.tap(find.text('28').last);
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await save(tester);
    expect(api.sent?.dateOfBirth, '2000-02-28');
  });

  testWidgets('back remains available while saving; no forced return on completion', (tester) async {
    final pending = Completer<Profile>();
    final api = Api()..pending = pending;
    await open(tester, api);
    await enter(tester, 'nickname', 'Changed');
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(api.puts, 1);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
    await tester.tap(find.byTooltip('Back'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.byType(EditProfileScreen), findsNothing);
    await tester.ensureVisible(find.text('Edit profile'));
    expect(tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed, isNull);
    pending.complete(Profile.fromJson({...api.sent!.toJson(), 'version': 8}));
    await tester.pumpAndSettle();
    expect(find.byType(EditProfileScreen), findsNothing);
    expect(api.puts, 1);
    expect(find.text('Changed'), findsOneWidget);
  });
}

class Api implements ProfileRepository {
  int gets = 0;
  int puts = 0;
  Profile? sent;
  Exception? failure;
  Completer<Profile>? pending;
  @override
  Future<Profile> getOwnProfile() async { gets++; return sample(); }
  @override
  Future<Profile> updateOwnProfile(Profile profile) async {
    puts++;
    sent = profile;
    if (failure != null) throw failure!;
    if (pending != null) return pending!.future;
    return Profile.fromJson({...profile.toJson(), 'version': 8});
  }
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
