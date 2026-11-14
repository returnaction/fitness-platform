enum ProfileVisibility { private, public }

class Profile {
  const Profile({
    required this.nickname,
    required this.firstName,
    required this.lastName,
    required this.bio,
    required this.dateOfBirth,
    required this.timezone,
    required this.visibility,
    required this.version,
  });

  final String nickname;
  final String? firstName;
  final String? lastName;
  final String? bio;
  // A calendar date, never converted through the device timezone.
  final String? dateOfBirth;
  final String timezone;
  final ProfileVisibility visibility;
  final int version;

  factory Profile.fromJson(Map<String, dynamic> json) {
    if (json case {
      'nickname': final String nickname,
      'firstName': final String? firstName,
      'lastName': final String? lastName,
      'bio': final String? bio,
      'dateOfBirth': final String? dateOfBirth,
      'timezone': final String timezone,
      'visibility': final String visibility,
      'version': final int version,
    }) {
      if (version < 1 || !_validDate(dateOfBirth)) {
        throw const FormatException('Invalid profile response.');
      }
      return Profile(
        nickname: nickname,
        firstName: firstName,
        lastName: lastName,
        bio: bio,
        dateOfBirth: dateOfBirth,
        timezone: timezone,
        visibility: switch (visibility) {
          'PRIVATE' => ProfileVisibility.private,
          'PUBLIC' => ProfileVisibility.public,
          _ => throw const FormatException('Invalid profile visibility.'),
        },
        version: version,
      );
    }
    throw const FormatException('Invalid profile response.');
  }

  Map<String, dynamic> toJson() => {
    'nickname': nickname,
    'firstName': firstName,
    'lastName': lastName,
    'bio': bio,
    'dateOfBirth': dateOfBirth,
    'timezone': timezone,
    'visibility': visibility == ProfileVisibility.public ? 'PUBLIC' : 'PRIVATE',
    'version': version,
  };

  static bool _validDate(String? value) {
    if (value == null) return true;
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) return false;
    final date = DateTime.tryParse(value);
    return date != null && date.toIso8601String().startsWith('$value' 'T');
  }
}
