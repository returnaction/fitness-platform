package io.github.returnaction.fitness.identity.registration.service;

import io.github.returnaction.fitness.identity.session.service.SessionTokenService;
import io.github.returnaction.fitness.identity.session.model.IssuedTokens;
import io.github.returnaction.fitness.identity.registration.dto.RegisterResponse;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.dto.RegisteredUser;
import io.github.returnaction.fitness.identity.registration.dto.RegistrationTokenResponse;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationNotReadyException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationUnavailableException;
import io.github.returnaction.fitness.identity.registration.model.RegistrationStartResult;
import io.github.returnaction.fitness.identity.registration.validation.IdempotencyKeyValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RegistrationFacade {

    private final RegistrationService registrationService;
    private final SessionTokenService tokenService;
    private final RegistrationCompletionWaiter readinessWaiter;
    private final IdempotencyKeyValidator keyValidator;

    /**
     * Завершает регистрацию пользователя и создаёт его первую сессию.
     *
     * <p>
     * Проверяет ключ идемпотентности, запускает или повторяет регистрацию, ожидает
     * готовности аккаунта и формирует ответ с данными пользователя и токенами
     * сессии.
     *
     * @param idempotencyKey
     *            ключ идемпотентности запроса
     * @param request
     *            данные для регистрации пользователя
     * @return данные зарегистрированного пользователя и токены первой сессии
     * @throws RegistrationUnavailableException
     *             если регистрация или выпуск токенов временно недоступны либо
     *             подготовка аккаунта не завершилась вовремя
     */
    public RegisterResponse register(String idempotencyKey, RegisterUserRequest request) {

        RegistrationStartResult operation = registrationService.start(keyValidator.parse(idempotencyKey), request);
        if (!readinessWaiter.await(operation.operationId())) {
            throw new RegistrationUnavailableException();
        }
        try {
            IssuedTokens tokens = tokenService.createForRegistration(operation.operationId(), request.password());

            return new RegisterResponse(
                    new RegisteredUser(
                            operation.userId(),
                            operation.emailCanonical(),
                            "ACTIVE"),
                    new RegistrationTokenResponse(
                            tokens.accessToken(),
                            tokens.refreshToken(),
                            "Bearer",
                            900,
                            2592000));

        } catch (RegistrationNotReadyException exception) {
            throw new RegistrationUnavailableException();
        }
    }
}
