package io.github.returnaction.fitness.identity.registration.application;

import java.util.List;
import java.util.Map;

/** Содержит только открытые параметры ключа для проверяющих компонентов. */
public final class PublicJwtKeys {
    private final Map<String, Object> key;

    PublicJwtKeys(Map<String, Object> key) {
        this.key = Map.copyOf(key);
    }

    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(key));
    }
}
