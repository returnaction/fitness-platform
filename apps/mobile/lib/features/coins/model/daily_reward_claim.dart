class DailyRewardClaim {
  const DailyRewardClaim({
    required this.claimedAmount,
    required this.availableBalance,
    required this.lockedBalance,
    required this.claimedForDate,
    required this.alreadyClaimed,
    required this.nextClaimAt,
    required this.serverTime,
  });

  factory DailyRewardClaim.fromJson(Map<String, dynamic> json) {
    return DailyRewardClaim(
      claimedAmount: _nonNegativeInt(json, 'claimedAmount'),
      availableBalance: _nonNegativeInt(json, 'availableBalance'),
      lockedBalance: _nonNegativeInt(json, 'lockedBalance'),
      claimedForDate: _string(json, 'claimedForDate'),
      alreadyClaimed: _bool(json, 'alreadyClaimed'),
      nextClaimAt: _instant(json, 'nextClaimAt'),
      serverTime: _instant(json, 'serverTime'),
    );
  }

  final int claimedAmount;
  final int availableBalance;
  final int lockedBalance;
  final String claimedForDate;
  final bool alreadyClaimed;
  final DateTime nextClaimAt;
  final DateTime serverTime;
}

bool _bool(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! bool) {
    throw FormatException('$key must be a boolean.');
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

String _string(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String || value.isEmpty) {
    throw FormatException('$key must be a non-empty string.');
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
