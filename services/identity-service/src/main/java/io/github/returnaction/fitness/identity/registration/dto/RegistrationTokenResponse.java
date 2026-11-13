package io.github.returnaction.fitness.identity.registration.dto;

public record RegistrationTokenResponse(String accessToken,
                                        String refreshToken,
                                        String tokenType,
                                        int expiresIn,
                                        int refreshExpiresIn) {

    @Override
    public String toString() {
        return "RegistrationTokenResponse[accessToken=<redacted>, refreshToken=<redacted>]";
    }
}
