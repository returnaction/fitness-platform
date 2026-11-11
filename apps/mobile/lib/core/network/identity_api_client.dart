import 'package:dio/dio.dart';

import '../../config/app_config.dart';
import 'identity_api_error.dart';

abstract interface class AuthApiClient {
  Future<Map<String, dynamic>> post(
    String path, {
    Object? data,
    Map<String, String>? headers,
  });

  Future<void> postWithoutResponse(
    String path, {
    Object? data,
    Map<String, String>? headers,
  });
}

class IdentityApiClient implements AuthApiClient {
  IdentityApiClient(Dio dio) : _dio = dio;

  factory IdentityApiClient.create() {
    return IdentityApiClient(
      Dio(
        BaseOptions(
          baseUrl: AppConfig.apiBaseUrl,
          connectTimeout: const Duration(seconds: 15),
          receiveTimeout: const Duration(seconds: 30),
          headers: const {'Accept': 'application/json'},
        ),
      ),
    );
  }

  final Dio _dio;

  @override
  Future<Map<String, dynamic>> post(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {
    try {
      final response = await _dio.post<dynamic>(
        path,
        data: data,
        options: Options(headers: headers),
      );
      return _asMap(response.data);
    } on DioException catch (error) {
      throw _toException(error);
    }
  }

  @override
  Future<void> postWithoutResponse(
    String path, {
    Object? data,
    Map<String, String>? headers,
  }) async {
    try {
      await _dio.post<void>(
        path,
        data: data,
        options: Options(headers: headers),
      );
    } on DioException catch (error) {
      throw _toException(error);
    }
  }

  static Map<String, dynamic> _asMap(dynamic value) {
    if (value is! Map) {
      throw const FormatException('Identity response must be an object.');
    }
    return Map<String, dynamic>.from(value);
  }

  static Exception _toException(DioException error) {
    final response = error.response;
    if (response == null) {
      return const IdentityNetworkException();
    }
    final body = response.data;
    final errorBody = body is Map ? body['error'] : null;
    final values = errorBody is Map
        ? Map<String, dynamic>.from(errorBody)
        : null;
    final code = values?['code'];
    final message = values?['message'];
    return IdentityApiException(
      statusCode: response.statusCode,
      code: code is String ? code : null,
      message: message is String ? message : 'Request could not be completed.',
      retryAfterSeconds: _retryAfterSeconds(response.headers.value('Retry-After')),
    );
  }

  static int? _retryAfterSeconds(String? value) =>
      value == null ? null : int.tryParse(value);
}
