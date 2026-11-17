class DailyRewardStatus {
  const DailyRewardStatus({
    required this.available,
    required this.amount,
    required this.nextClaimAt,
    required this.serverTime,
    required this.availableBalance,
    required this.lockedBalance,
  });

  factory DailyRewardStatus.fromJson(Map<String, dynamic> json) {
    final amount = _positiveInt(json, 'amount');
    return DailyRewardStatus(
      available: _bool(json, 'available'),
      amount: amount,
      nextClaimAt: _optionalInstant(json, 'nextClaimAt'),
      serverTime: _instant(json, 'serverTime'),
      availableBalance: _nonNegativeInt(json, 'availableBalance'),
      lockedBalance: _nonNegativeInt(json, 'lockedBalance'),
    );
  }

  final bool available;
  final int amount;
  final DateTime? nextClaimAt;
  final DateTime serverTime;
  final int availableBalance;
  final int lockedBalance;
}

String formatDailyRewardRemaining(Duration remaining) {
  final value = remaining.isNegative ? Duration.zero : remaining;
  final hours = value.inHours.toString().padLeft(2, '0');
  final minutes = (value.inMinutes % 60).toString().padLeft(2, '0');
  final seconds = (value.inSeconds % 60).toString().padLeft(2, '0');
  return '$hours:$minutes:$seconds';
}

bool _bool(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! bool) {
    throw FormatException('$key must be a boolean.');
  }
  return value;
}

int _positiveInt(Map<String, dynamic> json, String key) {
  final value = _nonNegativeInt(json, key);
  if (value == 0) {
    throw FormatException('$key must be positive.');
  }
  return value;
}

int _nonNegativeInt(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! int || value < 0) {
    throw FormatException('$key must be a non-negative integer.');
  }
  return value;
}

DateTime _instant(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String) {
    throw FormatException('$key must be a timestamp.');
  }
  return DateTime.parse(value).toUtc();
}

DateTime? _optionalInstant(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value == null) return null;
  if (value is! String) {
    throw FormatException('$key must be a timestamp or null.');
  }
  return DateTime.parse(value).toUtc();
}
