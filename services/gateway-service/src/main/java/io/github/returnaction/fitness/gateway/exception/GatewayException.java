package io.github.returnaction.fitness.gateway.exception;

public final class GatewayException extends RuntimeException {

    private final int status;
    private final String code;

    public GatewayException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }
    public String code() {
        return code;
    }
    public static GatewayException invalid() {
        return new GatewayException(401, "invalid_access_token");
    }
    public static GatewayException unavailable() {
        return new GatewayException(503, "identity_unavailable");
    }
}
