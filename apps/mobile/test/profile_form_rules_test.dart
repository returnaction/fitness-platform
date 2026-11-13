import 'package:fitness_app/features/profile/profile.dart';
import 'package:fitness_app/features/profile/profile_form_rules.dart';
import 'package:fitness_app/features/profile/profile_timezones.dart';
import 'package:flutter_test/flutter_test.dart';

Profile profile({String nickname = 'Nikita', String? firstName,
  String? lastName, String? bio, String? date, String timezone = 'UTC', int version = 1}) =>
    Profile(nickname: nickname, firstName: firstName, lastName: lastName, bio: bio,
      dateOfBirth: date, timezone: timezone, visibility: ProfileVisibility.private,
      version: version);

void main() {
  test('strip matches Java whitespace and preserves nonbreaking spaces', () {
    expect(stripProfileText('\t\u001c\u2003 Nikita \u3000\n'), 'Nikita');
    for (final char in ['\u00a0', '\u2007', '\u202f', '\ufeff']) {
      expect(stripProfileText('${char}Nikita$char'), '${char}Nikita$char');
    }
    expect(stripProfileText('Anna  Maria'), 'Anna  Maria');
  });
  test('normalization is shared by validation and unchanged comparison', () {
    final normalized = normalizeProfile(profile(nickname: ' Nikita  ', firstName: '   ',
      lastName: ' Example ', bio: '\n Hello\nthere \t'));
    expect(normalized.nickname, 'Nikita');
    expect(normalized.firstName, isNull);
    expect(normalized.lastName, 'Example');
    expect(normalized.bio, 'Hello\nthere');
    expect(sameProfileValues(profile(firstName: 'Nikita'),
      profile(firstName: 'Nikita   ', version: 2)), isTrue);
    expect(sameProfileValues(profile(), profile(nickname: 'nikita')), isFalse);
  });
  test('names allow letters and attached marks, but not digits or stray marks', () {
    for (final name in ['Анна Мария', "O'Connor", 'O’Connor', 'Jean-Luc', 'Jose\u0301']) {
      expect(validateProfile(profile(firstName: name)), isEmpty, reason: name);
    }
    for (final name in ['123', '--', '\u0301Anna', 'Anna \u0301', 'Anna\tMaria', 'Anna😀']) {
      expect(validateProfile(profile(firstName: name)), contains('firstName'));
    }
  });
  test('length is code points; nul is rejected', () {
    expect(validateProfile(profile(bio: '😀' * 500)), isEmpty);
    expect(validateProfile(profile(bio: '😀' * 501)), contains('bio'));
    expect(validateProfile(profile(firstName: '𐐀' * 100)), isEmpty);
    expect(validateProfile(profile(firstName: '𐐀' * 101)), contains('firstName'));
    expect(validateProfile(profile(bio: 'a\u0000b')), contains('bio'));
  });
  test('nickname remains required and ASCII after stripping', () {
    for (final nickname in ['', '  ', 'ab', 'Никита', 'a b', 'a' * 31]) {
      expect(validateProfile(profile(nickname: nickname)), contains('nickname'));
    }
    expect(validateProfile(profile(nickname: '  Nikita_1-  ')), isEmpty);
  });
  test('date range uses UTC and clamps subtract-years on leap day', () {
    final now = DateTime.parse('2024-03-01T01:00:00+03:00');
    expect(profileToday(now), DateTime.utc(2024, 2, 29));
    expect(earliestProfileDate(profileToday(now)), DateTime.utc(1874, 2, 28));
    for (final date in ['1874-02-28', '2024-02-29']) {
      expect(validateProfile(profile(date: date), now: now), isEmpty);
    }
    for (final date in ['1874-02-27', '2024-03-01', '2023-02-29']) {
      expect(validateProfile(profile(date: date), now: now), contains('dateOfBirth'));
    }
  });
  test('timezone choices include server IDs and reject arbitrary offsets', () {
    expect(profileTimezones, containsAll(['UTC', 'Europe/Moscow', 'America/New_York']));
    expect(validateProfile(profile(timezone: 'Europe/Moscow')), isEmpty);
    expect(validateProfile(profile(timezone: '+03:00')), contains('timezone'));
    expect(validateProfile(profile(timezone: ' UTC ')), contains('timezone'));
  });
}
