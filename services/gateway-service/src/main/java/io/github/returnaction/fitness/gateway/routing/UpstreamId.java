package io.github.returnaction.fitness.gateway.routing;

/**
 * Идентификаторы upstream-сервисов Gateway.
 *
 * <p>Spec 017 T003.1: добавлен {@code COINS} для проксирования баланса.</p>
 */
public enum UpstreamId {
    IDENTITY,
    ACCOUNT,
    COINS
}
