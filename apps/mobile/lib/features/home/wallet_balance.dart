class WalletBalance {
  const WalletBalance({
    required this.availableBalance,
    required this.lockedBalance,
  });

  static const _maxInt64 = 9223372036854775807;

  final int availableBalance;
  final int lockedBalance;

  factory WalletBalance.fromJson(Map<String, dynamic> json) => WalletBalance(
    availableBalance: _readBalance(json, 'availableBalance'),
    lockedBalance: _readBalance(json, 'lockedBalance'),
  );

  static int _readBalance(Map<String, dynamic> json, String field) {
    final value = json[field];
    if (value is! int || value < 0 || value > _maxInt64) {
      throw FormatException('Wallet response has an invalid $field.');
    }
    return value;
  }
}

String formatCoins(int value) {
  final digits = value.toString();
  final firstGroupLength = digits.length % 3;
  final buffer = StringBuffer();
  var index = 0;
  if (firstGroupLength > 0) {
    buffer.write(digits.substring(0, firstGroupLength));
    index = firstGroupLength;
  }
  while (index < digits.length) {
    if (buffer.isNotEmpty) buffer.write(',');
    buffer.write(digits.substring(index, index + 3));
    index += 3;
  }
  return buffer.toString();
}
