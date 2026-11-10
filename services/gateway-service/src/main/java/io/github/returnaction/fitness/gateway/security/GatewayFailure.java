package io.github.returnaction.fitness.gateway.security;

public final class GatewayFailure extends RuntimeException {
    private final int status;
    private final String code;

    public GatewayFailure(int status, String code) {
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
    public static GatewayFailure invalid() {
        return new GatewayFailure(401, "invalid_access_token");
    }
    public static GatewayFailure unavailable() {
        return new GatewayFailure(503, "identity_unavailable");
    }
}
