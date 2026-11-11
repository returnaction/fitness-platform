import 'dart:async';

import 'package:fitness_app/features/auth/session_operation_coordinator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'does not start a second session operation before the first finishes',
    () async {
      final coordinator = SessionOperationCoordinator();
      final firstCanFinish = Completer<void>();
      final events = <String>[];

      final first = coordinator.run(() async {
        events.add('first-start');
        await firstCanFinish.future;
        events.add('first-finish');
      });
      final second = coordinator.run(() async {
        events.add('second-start');
      });

      await Future<void>.delayed(Duration.zero);
      expect(events, ['first-start']);

      firstCanFinish.complete();
      await Future.wait([first, second]);

      expect(events, ['first-start', 'first-finish', 'second-start']);
    },
  );
}
