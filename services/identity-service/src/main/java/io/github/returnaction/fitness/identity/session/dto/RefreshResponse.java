package io.github.returnaction.fitness.identity.session.dto;

public record RefreshResponse(String accessToken,
        String refreshToken,
        String tokenType,
        int expiresIn,
        long refreshExpiresIn) {

    @Override
    public String toString() {
        return "RefreshResponse[accessToken=<redacted>, refreshToken=<redacted>]";
    }
}
