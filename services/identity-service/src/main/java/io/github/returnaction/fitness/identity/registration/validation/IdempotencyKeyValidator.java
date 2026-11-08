package io.github.returnaction.fitness.identity.registration.validation;

import io.github.returnaction.fitness.identity.registration.exception.InvalidIdempotencyKeyException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyValidator {
    public UUID parse(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value) || uuid.version() != 4 || uuid.variant() != 2) {
                throw new InvalidIdempotencyKeyException();
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
