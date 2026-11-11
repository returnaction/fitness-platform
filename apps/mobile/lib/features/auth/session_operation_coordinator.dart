class SessionOperationCoordinator {
  Future<void> _lastOperation = Future<void>.value();

  Future<T> run<T>(Future<T> Function() operation) {
    final scheduled = _lastOperation.then((_) => operation());
    _lastOperation = scheduled.then<void>((_) {}, onError: (_, _) {});
    return scheduled;
  }
}
