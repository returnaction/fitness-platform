class SessionTokens {
  const SessionTokens({required this.accessToken, required this.refreshToken});

  final String accessToken;
  final String refreshToken;

  Map<String, String> toJson() => {
    'access_token': accessToken,
    'refresh_token': refreshToken,
  };

  factory SessionTokens.fromJson(Map<String, dynamic> json) {
    final accessToken = json['access_token'];
    final refreshToken = json['refresh_token'];
    if (accessToken is! String ||
        accessToken.isEmpty ||
        refreshToken is! String ||
        refreshToken.isEmpty) {
      throw const FormatException(
        'Identity response is missing session tokens.',
      );
    }
    return SessionTokens(accessToken: accessToken, refreshToken: refreshToken);
  }
}
