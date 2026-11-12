package io.github.returnaction.fitness.gateway.security.session;

import io.github.returnaction.fitness.gateway.timeout.RequestDeadline;
import java.util.UUID;

public interface SessionVerificationClient {

    /**
     * true — Identity подтвердил ACTIVE; false — подтвердил INVALID. Технический
     * отказ завершается исключением, а не false.
     */
    boolean isActive(UUID userId, UUID sessionId, UUID requestId, RequestDeadline deadline);
}
