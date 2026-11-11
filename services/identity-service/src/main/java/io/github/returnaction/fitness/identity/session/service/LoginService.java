package io.github.returnaction.fitness.identity.session.service;

import io.github.returnaction.fitness.identity.session.dto.LoginRequest;
import io.github.returnaction.fitness.identity.session.dto.LoginTokenResponse;
import io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException;
import static io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException.Reason.INVALID_CREDENTIALS;
import static io.github.returnaction.fitness.identity.session.exception.AuthenticationFailureException.Reason.REGISTRATION_PENDING;
import io.github.returnaction.fitness.identity.shared.model.ValidatedCredentials;
import io.github.returnaction.fitness.identity.shared.validation.CredentialsValidator;
import io.github.returnaction.fitness.identity.session.model.IssuedTokens;
import io.github.returnaction.fitness.identity.registration.entity.user.UserStatus;
import io.github.returnaction.fitness.identity.session.repository.LoginRepository;
import io.github.returnaction.fitness.identity.session.model.LoginUser;
import java.util.Optional;
import io.github.returnaction.fitness.identity.shared.exception.LoginCredentialsChangedException;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Проверяет учётные данные и ускоряет только безопасную незаблокированную
 * операцию.
 */
@Service
public final class LoginService {

    private final LoginRepository loginRepository;
    private final PasswordEncoder passwordEncoder;
    private final CredentialsValidator validator;
    private final String dummyHash;
    private final SessionTokenService tokenService;

    public LoginService(LoginRepository loginRepository,
                        PasswordEncoder passwordEncoder,
                        CredentialsValidator validator,
                        SessionTokenService tokenService) {
        this.loginRepository = loginRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginTokenResponse login(LoginRequest request) {

        ValidatedCredentials credentials = validator.normalize(request.email(), request.password());

        try {
            return authenticate(credentials);
        } catch (LoginCredentialsChangedException exception) {
            return retryAfterCredentialsChange(credentials);
        }
    }

    private LoginTokenResponse authenticate(ValidatedCredentials credentials) {
        Optional<LoginUser> candidate = loginRepository.findByEmail(credentials.emailCanonical());
        LoginUser user = verifyPassword(candidate, credentials.password());
        ensureAccountIsActive(user);

        String passwordHash = user.passwordHash();
        String upgradedHash = passwordEncoder.upgradeEncoding(passwordHash)
                ? passwordEncoder.encode(credentials.password())
                : null;
        IssuedTokens tokens = tokenService.createForLogin(user.id(), passwordHash, upgradedHash);
        return new LoginTokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer", 900, 2592000);
    }

    private LoginUser verifyPassword(Optional<LoginUser> candidate, String password) {
        String passwordHash = candidate.map(LoginUser::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, passwordHash);
        if (!matches || candidate.isEmpty()) {
            throw new AuthenticationFailureException(INVALID_CREDENTIALS);
        }
        return candidate.orElseThrow();
    }

    private void ensureAccountIsActive(LoginUser user) {
        if (user.status() == UserStatus.PENDING) {
            loginRepository.accelerate(user.id(), user.passwordHash());
            throw new AuthenticationFailureException(REGISTRATION_PENDING);
        }
        if (user.status() != UserStatus.ACTIVE) {
            throw new AuthenticationFailureException(AuthenticationFailureException.Reason.ACCOUNT_UNAVAILABLE);
        }
    }

    private LoginTokenResponse retryAfterCredentialsChange(ValidatedCredentials credentials) {
        try {
            return authenticate(credentials);
        } catch (LoginCredentialsChangedException exception) {
            throw new AuthenticationFailureException(INVALID_CREDENTIALS);
        }
    }
}
