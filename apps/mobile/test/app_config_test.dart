import 'package:fitness_app/config/app_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('uses the Gateway address for Android Emulator by default', () {
    expect(AppConfig.apiBaseUrl, 'http://10.0.2.2:8088');
  });
}
