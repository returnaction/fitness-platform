package io.github.returnaction.fitness.identity.registration.application;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создание сессии и подписание access token фиксируются вместе. */
@Service
@ConditionalOnProperty(name = "identity.jwt.enabled", havingValue = "true")
public class RegistrationTokenService {
    private final RegistrationSessionService sessions;
    private final AccessTokenSigner signer;

    public RegistrationTokenService(RegistrationSessionService sessions, AccessTokenSigner signer) {
        this.sessions = sessions;
        this.signer = signer;
    }

    @Transactional
    public Tokens create(UUID operationId, String password) {
        var session = sessions.create(operationId, password);
        String access = signer.sign(session.userId(), session.sessionId(), session.expiresAt());
        return new Tokens(access, session.refreshToken());
    }

    public static final class Tokens {
        private final String accessToken;
        private final String refreshToken;

        Tokens(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public String accessToken() {
            return accessToken;
        }

        public String refreshToken() {
            return refreshToken;
        }
    }
}
