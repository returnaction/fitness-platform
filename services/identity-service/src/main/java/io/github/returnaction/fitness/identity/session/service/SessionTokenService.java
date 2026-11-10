package io.github.returnaction.fitness.identity.session.service;

import io.github.returnaction.fitness.identity.registration.service.RegistrationSessionService;
import io.github.returnaction.fitness.identity.session.model.IssuedTokens;

import io.github.returnaction.fitness.identity.security.jwt.AccessTokenSigner;
import io.github.returnaction.fitness.identity.registration.repository.UserRoleRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Создание сессии и подписание access token фиксируются вместе. */
@Service
@RequiredArgsConstructor
public class SessionTokenService {

    private final RegistrationSessionService sessions;
    private final AccessTokenSigner signer;
    private final UserRoleRepository roles;

    @Transactional
    public IssuedTokens create(UUID operationId, String password) {

        RegistrationSessionService.IssuedSession session = sessions.create(operationId, password);
        String access = signer.sign(session.userId(), session.sessionId(), session.expiresAt(),
                roles.findRoleCodesByUserId(session.userId()));

        return new IssuedTokens(access, session.refreshToken());
    }

    @Transactional
    public IssuedTokens createLogin(UUID userId, String passwordHash, String upgradedHash) {

        RegistrationSessionService.IssuedSession session = sessions.createLogin(userId, passwordHash, upgradedHash);
        String access = signer.sign(session.userId(), session.sessionId(), session.expiresAt(),
                roles.findRoleCodesByUserId(session.userId()));

        return new IssuedTokens(access, session.refreshToken());
    }

}
