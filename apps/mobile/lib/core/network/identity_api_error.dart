class IdentityApiException implements Exception {
  const IdentityApiException({
    required this.statusCode,
    required this.code,
    required this.message,
    this.retryAfterSeconds,
  });

  final int? statusCode;
  final String? code;
  final String message;
  final int? retryAfterSeconds;

  bool get isIdentityUnavailable =>
      statusCode == 503 && code == 'identity_unavailable';
}

class IdentityNetworkException implements Exception {
  const IdentityNetworkException();
}
