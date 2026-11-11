class IdentityApiException implements Exception {
  const IdentityApiException({
    required this.statusCode,
    required this.code,
    required this.message,
  });

  final int? statusCode;
  final String? code;
  final String message;
}

class IdentityNetworkException implements Exception {
  const IdentityNetworkException();
}
