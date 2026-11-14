class IdentityApiException implements Exception {
  const IdentityApiException({
    required this.statusCode,
    required this.code,
    required this.message,
    this.retryAfterSeconds,
    this.details = const [],
    this.requestId,
  });

  final int? statusCode;
  final String? code;
  final String message;
  final int? retryAfterSeconds;
  final List<ApiFieldError> details;
  final String? requestId;

  bool get isIdentityUnavailable =>
      statusCode == 503 && code == 'identity_unavailable';
}

class IdentityNetworkException implements Exception {
  const IdentityNetworkException();
}

class ApiFieldError {
  const ApiFieldError({required this.field, required this.code});

  final String field;
  final String code;
}
