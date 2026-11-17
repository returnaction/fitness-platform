package io.github.returnaction.fitness.challenge.coins.exception;

/**
 * Coins отклонила команду по постоянной причине. Повтор с тем же payload
 * бессмысленен без изменения состояния или данных.
 */
public final class CoinsRejectedException extends RuntimeException {

    private final String grpcCode;
    private final String reason;

    public CoinsRejectedException(String grpcCode, String reason, Throwable cause) {
        super("Coins rejected the command.", cause);
        this.grpcCode = grpcCode;
        this.reason = reason;
    }

    public String getGrpcCode() {
        return grpcCode;
    }

    public String getReason() {
        return reason;
    }
}
