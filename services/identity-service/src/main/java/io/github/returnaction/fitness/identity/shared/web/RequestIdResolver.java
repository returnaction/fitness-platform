package io.github.returnaction.fitness.identity.shared.web;

import java.util.UUID;

public final class RequestIdResolver {

    private RequestIdResolver() {}

    public static UUID resolve(String requestIdHeader) {
        if (requestIdHeader == null || requestIdHeader.isBlank()) {
            return UUID.randomUUID();
        }

        try {
            return UUID.fromString(requestIdHeader);
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID();
        }
    }
}
