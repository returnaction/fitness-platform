package io.github.returnaction.fitness.identity.session.service;

import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.model.ValidatedRegistration;
import io.github.returnaction.fitness.identity.registration.service.RegistrationInputValidator;
import io.github.returnaction.fitness.identity.registration.service.RegistrationTokenService;
import io.github.returnaction.fitness.identity.session.repository.PendingLoginRepository;
import io.github.returnaction.fitness.identity.shared.exception.LoginCredentialsChangedException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Проверяет учётные данные и ускоряет только безопасную незаблокированную
 * операцию.
 */
@Service
public final class PendingLoginService {

    private final PendingLoginRepository repository;
    private final PasswordEncoder encoder;
    private final RegistrationInputValidator validator;
    private final String dummyHash;
    private final ObjectProvider<RegistrationTokenService> tokens;

    public PendingLoginService(PendingLoginRepository repository,
                               PasswordEncoder encoder,
                               RegistrationInputValidator validator,
                               ObjectProvider<RegistrationTokenService> tokens) {
        this.repository = repository;
        this.encoder = encoder;
        this.validator = validator;
        this.tokens = tokens;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public Result login(RegisterUserRequest request) {
        var input = validator.validate(request);
        return loginValidated(input, true);
    }

    private Result loginValidated(ValidatedRegistration input, boolean mayRetry) {
        var user = repository.findUser(input.emailCanonical());
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean matches = encoder.matches(input.password(), hash);
        if (user == null || !matches) {
            return new Result(Outcome.INVALID, null);
        }
        if (!"PENDING".equals(user.status()) && !"ACTIVE".equals(user.status())) {
            return new Result(Outcome.ACCOUNT_UNAVAILABLE, null);
        }
        if ("ACTIVE".equals(user.status())) {
            var issuer = tokens.getIfAvailable();
            if (issuer == null)
                return new Result(Outcome.ACTIVE_LOGIN_UNAVAILABLE, null);
            String upgraded = encoder.upgradeEncoding(hash) ? encoder.encode(input.password()) : null;
            try {
                return new Result(Outcome.ACTIVE, issuer.createLogin(user.id(), hash, upgraded));
            } catch (LoginCredentialsChangedException exception) {
                return mayRetry ? loginValidated(input, false) : new Result(Outcome.INVALID, null);
            }
        }
        repository.accelerate(user.id(), hash);
        return new Result(Outcome.PENDING, null);
    }

    public enum Outcome {
        INVALID, ACCOUNT_UNAVAILABLE, PENDING, ACTIVE, ACTIVE_LOGIN_UNAVAILABLE
    }

    public static final class Result {
        private final Outcome outcome;
        private final RegistrationTokenService.Tokens tokens;

        Result(Outcome outcome, RegistrationTokenService.Tokens tokens) {
            this.outcome = outcome;
            this.tokens = tokens;
        }

        public Outcome outcome() {
            return outcome;
        }

        public RegistrationTokenService.Tokens tokens() {
            return tokens;
        }
    }
}
