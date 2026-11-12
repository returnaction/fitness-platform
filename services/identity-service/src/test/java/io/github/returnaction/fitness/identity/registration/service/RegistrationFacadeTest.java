package io.github.returnaction.fitness.identity.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.returnaction.fitness.identity.session.model.IssuedTokens;
import io.github.returnaction.fitness.identity.session.service.SessionTokenService;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationNotReadyException;
import io.github.returnaction.fitness.identity.registration.exception.RegistrationUnavailableException;
import io.github.returnaction.fitness.identity.registration.model.RegistrationStartResult;
import io.github.returnaction.fitness.identity.registration.validation.IdempotencyKeyValidator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrationFacadeTest {

    @Mock
    private RegistrationService registration;

    @Mock
    private SessionTokenService tokenService;

    @Mock
    private RegistrationCompletionWaiter waiter;

    @Test
    void registrationBecomingUnavailableAfterWaitStillReportsUnavailable() {
        UUID key = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var request = new RegisterUserRequest("user@example.com", "a");
        when(registration.start(key, request))
                .thenReturn(new RegistrationStartResult(operation, UUID.randomUUID(), "user@example.com"));
        when(waiter.await(operation)).thenReturn(true);
        when(tokenService.createForRegistration(operation, "a")).thenThrow(new RegistrationNotReadyException());
        var facade = new RegistrationFacade(registration, tokenService, waiter, new IdempotencyKeyValidator());
        assertThatThrownBy(() -> facade.register(key.toString(), request))
                .isInstanceOf(RegistrationUnavailableException.class);
    }

    @Test
    void completedRegistrationReturnsUserAndTokens() {
        UUID key = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        var request = new RegisterUserRequest("user@example.com", "a");
        when(registration.start(key, request))
                .thenReturn(new RegistrationStartResult(operation, user, "user@example.com"));
        when(waiter.await(operation)).thenReturn(true);
        when(tokenService.createForRegistration(operation, "a")).thenReturn(new IssuedTokens("access", "refresh"));
        var facade = new RegistrationFacade(registration, tokenService, waiter, new IdempotencyKeyValidator());
        var response = facade.register(key.toString(), request);
        assertThat(response.user().id()).isEqualTo(user);
        assertThat(response.session().accessToken()).isEqualTo("access");
        assertThat(response.session().refreshToken()).isEqualTo("refresh");
    }
}
