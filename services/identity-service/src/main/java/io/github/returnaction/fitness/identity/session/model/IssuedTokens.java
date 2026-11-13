package io.github.returnaction.fitness.identity.session.model;

/** Внутренний результат выпуска токенов сессии. */
public record IssuedTokens(String accessToken,
                           String refreshToken) {

    @Override
    public String toString() {
        return "IssuedTokens[redacted]";
    }
}
