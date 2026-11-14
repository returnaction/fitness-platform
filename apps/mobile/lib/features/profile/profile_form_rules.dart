import 'package:flutter/foundation.dart';

import 'profile.dart';
import 'profile_timezones.dart';

// Character.isWhitespace / String.strip on the Accounts JDK. Dart trim also
// removes nonbreaking spaces, which the server deliberately does not remove.
String stripProfileText(String value) {
  var start = 0;
  var end = value.length;
  while (start < end && _isWhitespace(value.codeUnitAt(start))) { start++; }
  while (end > start && _isWhitespace(value.codeUnitAt(end - 1))) { end--; }
  return value.substring(start, end);
}

bool _isWhitespace(int c) =>
    (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x20) ||
    c == 0x1680 || (c >= 0x2000 && c <= 0x2006) ||
    (c >= 0x2008 && c <= 0x200a) || c == 0x2028 || c == 0x2029 ||
    c == 0x205f || c == 0x3000;

String? _optional(String? value) {
  final normalized = value == null ? '' : stripProfileText(value);
  return normalized.isEmpty ? null : normalized;
}

Profile normalizeProfile(Profile profile) => Profile(
  nickname: stripProfileText(profile.nickname),
  firstName: _optional(profile.firstName),
  lastName: _optional(profile.lastName),
  bio: _optional(profile.bio),
  dateOfBirth: profile.dateOfBirth,
  timezone: profile.timezone,
  visibility: profile.visibility,
  version: profile.version,
);

bool sameProfileValues(Profile left, Profile right) {
  final a = normalizeProfile(left).toJson()..remove('version');
  final b = normalizeProfile(right).toJson()..remove('version');
  return mapEquals(a, b);
}

DateTime profileToday(DateTime now) {
  final utc = now.toUtc();
  return DateTime.utc(utc.year, utc.month, utc.day);
}

DateTime earliestProfileDate(DateTime today) {
  final year = today.year - 150;
  final lastDay = DateTime.utc(year, today.month + 1, 0).day;
  return DateTime.utc(year, today.month, today.day > lastDay ? lastDay : today.day);
}

String profileDateString(DateTime date) =>
    '${date.year.toString().padLeft(4, '0')}-'
    '${date.month.toString().padLeft(2, '0')}-'
    '${date.day.toString().padLeft(2, '0')}';

final _nickname = RegExp(r'[A-Za-z0-9_-]+');
final _name = RegExp(r"(?:\p{L}\p{M}*|[ '’-])+", unicode: true);
final _letter = RegExp(r'\p{L}', unicode: true);

bool _matchesAll(RegExp pattern, String value) {
  final match = pattern.matchAsPrefix(value);
  return match != null && match.end == value.length;
}

Map<String, String> validateProfile(Profile input, {DateTime? now}) {
  final profile = normalizeProfile(input);
  final errors = <String, String>{};
  final nickname = profile.nickname;
  if (nickname.isEmpty) {
    errors['nickname'] = 'Enter a nickname.';
  } else if (nickname.runes.length < 3 || nickname.runes.length > 30 ||
      !_matchesAll(_nickname, nickname)) {
    errors['nickname'] = 'Use 3–30 letters, numbers, underscores or hyphens.';
  }
  for (final entry in {'firstName': profile.firstName, 'lastName': profile.lastName}.entries) {
    final value = entry.value;
    if (value == null) continue;
    if (value.runes.length > 100) {
      errors[entry.key] = 'Use at most 100 characters.';
    } else if (!_letter.hasMatch(value) || !_matchesAll(_name, value)) {
      errors[entry.key] = 'Use letters, spaces, hyphens or apostrophes.';
    }
  }
  final bio = profile.bio;
  if (bio != null && bio.runes.length > 500) {
    errors['bio'] = 'Use at most 500 characters.';
  } else if (bio != null && bio.contains('\u0000')) {
    errors['bio'] = 'Remove unsupported characters.';
  }
  final rawDate = profile.dateOfBirth;
  if (rawDate != null) {
    final date = DateTime.tryParse('${rawDate}T00:00:00Z');
    final today = profileToday(now ?? DateTime.now());
    if (date == null || profileDateString(date) != rawDate ||
        date.isBefore(earliestProfileDate(today)) || date.isAfter(today)) {
      errors['dateOfBirth'] = 'Choose a date within the last 150 years, up to today.';
    }
  }
  if (!profileTimezones.contains(profile.timezone)) {
    errors['timezone'] = 'Select a timezone from the list.';
  }
  return errors;
}

String profileFieldError(String field, String code) => switch (code) {
  'required' => 'This field is required.',
  'invalid_length' => field == 'bio' ? 'Use at most 500 characters.'
      : field == 'nickname' ? 'Use 3–30 characters.' : 'Use at most 100 characters.',
  'invalid_format' => field == 'nickname'
      ? 'Use letters, numbers, underscores or hyphens.'
      : field == 'firstName' || field == 'lastName'
          ? 'Use letters, spaces, hyphens or apostrophes.' : 'Check this value.',
  'out_of_range' => 'Choose a value within the allowed range.',
  'invalid_value' => 'Select a valid value.',
  _ => 'Check this value.',
};
