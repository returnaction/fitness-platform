package io.github.returnaction.fitness.identity.session.dto;

public record LoginTokenResponse(String accessToken,
                                 String refreshToken,
                                 String tokenType,
                                 int expiresIn,
                                 int refreshExpiresIn) {

    @Override
    public String toString() {
        return "LoginTokenResponse[accessToken=<redacted>, refreshToken=<redacted>]";
    }
}
