package io.github.returnaction.fitness.identity.registration.security;

import io.github.returnaction.fitness.identity.registration.exception.RecoveryAccessException;
import io.github.returnaction.fitness.identity.registration.repository.RecoveryAccessRepository;
import io.github.returnaction.fitness.identity.security.jwt.AccessTokenVerifier;
import io.github.returnaction.fitness.identity.session.repository.SessionVerificationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecoveryAccessVerifier {
    private final AccessTokenVerifier verifier;
    private final SessionVerificationRepository sessions;
    private final RecoveryAccessRepository repository;

    public UUID verify(String token) {
        var identity = verifier.verify(token);
        if (!sessions.check(identity.userId(), identity.sessionId()).active())
            throw new RecoveryAccessException("invalid_access_token");
        if (!repository.isActiveSuperAdmin(identity.userId()))
            throw new RecoveryAccessException("forbidden");
        return identity.userId();
    }
}
