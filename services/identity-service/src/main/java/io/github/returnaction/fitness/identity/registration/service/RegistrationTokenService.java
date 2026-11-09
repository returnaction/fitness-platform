package io.github.returnaction.fitness.identity.registration.service;

import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создание сессии и подписание access token фиксируются вместе. */
@Service
@ConditionalOnProperty(name = "identity.jwt.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RegistrationTokenService {
    private final RegistrationSessionService sessions;
    private final AccessTokenSigner signer;
    private final UserRoleRepository roles;

    @Transactional
    public Tokens create(UUID operationId, String password) {

        RegistrationSessionService.IssuedSession session = sessions.create(operationId, password);
        String access = signer.sign(session.userId(), session.sessionId(), session.expiresAt(),
                roles.findRoleCodesByUserId(session.userId()));

        return new Tokens(access, session.refreshToken());
    }

    @Transactional
    public Tokens createLogin(UUID userId, String passwordHash, String upgradedHash) {

        RegistrationSessionService.IssuedSession session = sessions.createLogin(userId, passwordHash, upgradedHash);
        String access = signer.sign(session.userId(), session.sessionId(), session.expiresAt(),
                roles.findRoleCodesByUserId(session.userId()));

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
