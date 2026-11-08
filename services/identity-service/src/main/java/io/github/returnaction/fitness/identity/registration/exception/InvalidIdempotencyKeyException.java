package io.github.returnaction.fitness.identity.registration.exception;

/**
 * Клиент прислал неправильный Idempotency-Key. Например, вместо UUID версии 4
 * передал строку «abc».
 */
public class InvalidIdempotencyKeyException extends RuntimeException {
}
