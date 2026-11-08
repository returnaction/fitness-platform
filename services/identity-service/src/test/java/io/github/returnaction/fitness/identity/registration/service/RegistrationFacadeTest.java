package io.github.returnaction.fitness.identity.registration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class RegistrationFacadeTest {
    @Mock
    private RegistrationService registration;

    @Mock
    private ObjectProvider<RegistrationTokenService> tokenProvider;

    @Mock
    private RegistrationTokenService tokenService;

    @Mock
    private RegistrationReadinessWaiter waiter;

    @Test
    void missingTokenServiceDoesNotWaitAndReportsUnavailable() {
        UUID key = UUID.randomUUID();
        var request = new RegisterUserRequest("user@example.com", "a");
        when(registration.start(key, request))
                .thenReturn(new RegistrationStartResult(UUID.randomUUID(), UUID.randomUUID(), "user@example.com"));
        var facade = new RegistrationFacade(registration, tokenProvider, waiter, new IdempotencyKeyValidator());
        assertThatThrownBy(() -> facade.register(key.toString(), request))
                .isInstanceOf(RegistrationUnavailableException.class);
        verifyNoInteractions(waiter);
    }

    @Test
    void registrationBecomingUnavailableAfterWaitStillReportsUnavailable() {
        UUID key = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var request = new RegisterUserRequest("user@example.com", "a");
        when(registration.start(key, request))
                .thenReturn(new RegistrationStartResult(operation, UUID.randomUUID(), "user@example.com"));
        when(tokenProvider.getIfAvailable()).thenReturn(tokenService);
        when(waiter.await(operation)).thenReturn(true);
        when(tokenService.create(operation, "a")).thenThrow(new RegistrationNotReadyException());
        var facade = new RegistrationFacade(registration, tokenProvider, waiter, new IdempotencyKeyValidator());
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
        when(tokenProvider.getIfAvailable()).thenReturn(tokenService);
        when(waiter.await(operation)).thenReturn(true);
        when(tokenService.create(operation, "a")).thenReturn(new RegistrationTokenService.Tokens("access", "refresh"));
        var facade = new RegistrationFacade(registration, tokenProvider, waiter, new IdempotencyKeyValidator());
        var response = facade.register(key.toString(), request);
        assertThat(response.user().id()).isEqualTo(user);
        assertThat(response.session().accessToken()).isEqualTo("access");
        assertThat(response.session().refreshToken()).isEqualTo("refresh");
    }
}
