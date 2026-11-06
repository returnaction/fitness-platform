package io.github.returnaction.fitness.identity.registration.controller;

import io.github.returnaction.fitness.identity.registration.application.InvalidIdempotencyKeyException;
import io.github.returnaction.fitness.identity.registration.application.RegistrationService;
import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.shared.web.ApiErrorResponse;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import io.github.returnaction.fitness.identity.shared.web.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
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

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    ResponseEntity<ApiErrorResponse> register(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RegisterUserRequest request,
            HttpServletRequest servletRequest) {
        registrationService.start(parseIdempotencyKey(idempotencyKey), request);
        String requestId = requestId(servletRequest);
        ApiErrorResponse body = new ApiErrorResponse(new ApiErrorResponse.Error(
                "registration_unavailable", "Registration is being prepared.", List.of(), requestId));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private UUID parseIdempotencyKey(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (uuid.version() != 4) {
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
