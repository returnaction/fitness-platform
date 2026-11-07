package io.github.returnaction.fitness.identity.registration.controller;

import io.github.returnaction.fitness.identity.registration.application.InvalidIdempotencyKeyException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationNotReadyException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationReadinessWaiter;
import io.github.returnaction.fitness.identity.registration.application.RegistrationService;
import io.github.returnaction.fitness.identity.registration.application.RegistrationTokenService;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.shared.web.ApiErrorResponse;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import io.github.returnaction.fitness.identity.shared.web.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final ObjectProvider<RegistrationTokenService> tokenService;
    private final RegistrationReadinessWaiter readinessWaiter;

    public RegistrationController(
            RegistrationService registrationService,
            ObjectProvider<RegistrationTokenService> tokenService,
            RegistrationReadinessWaiter readinessWaiter) {
        this.registrationService = registrationService;
        this.tokenService = tokenService;
        this.readinessWaiter = readinessWaiter;
    }

    @PostMapping("/register")
    ResponseEntity<?> register(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RegisterUserRequest request,
            HttpServletRequest servletRequest) {
        var operation = registrationService.start(parseIdempotencyKey(idempotencyKey), request);
        var issuer = tokenService.getIfAvailable();
        if (issuer != null && readinessWaiter.await(operation.operationId())) {
            try {
                var tokens = issuer.create(operation.operationId(), request.password());
                return ResponseEntity.status(HttpStatus.CREATED)
                        .header("Cache-Control", "no-store")
                        .body(new RegisterResponse(
                                new RegisteredUser(operation.userId(), operation.emailCanonical(), "ACTIVE"),
                                new TokenResponse(
                                        tokens.accessToken(), tokens.refreshToken(), "Bearer", 900, 2592000)));
            } catch (RegistrationNotReadyException ignored) {
                // Фоновая подготовка ещё не завершилась; клиент повторяет запрос.
            }
        }
        String requestId = requestId(servletRequest);
        ApiErrorResponse body = new ApiErrorResponse(new ApiErrorResponse.Error(
                "registration_unavailable", "Registration is being prepared.", List.of(), requestId));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private record RegisterResponse(RegisteredUser user, TokenResponse session) {}

    private record RegisteredUser(UUID id, String email, String status) {}

    private record TokenResponse(
            String accessToken, String refreshToken, String tokenType, int expiresIn, int refreshExpiresIn) {}

    private UUID parseIdempotencyKey(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value) || uuid.version() != 4 || uuid.variant() != 2) {
                throw new InvalidIdempotencyKeyException();
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId instanceof String value
                ? value
                : RequestIdResolver.resolve(null).toString();
    }
}
