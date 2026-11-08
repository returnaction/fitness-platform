package io.github.returnaction.fitness.identity.session.controller;

import io.github.returnaction.fitness.identity.registration.dto.RegisterUserRequest;
import io.github.returnaction.fitness.identity.session.dto.LoginTokenResponse;
import io.github.returnaction.fitness.identity.session.service.PendingLoginService;
import io.github.returnaction.fitness.identity.shared.web.ApiErrorResponse;
import io.github.returnaction.fitness.identity.shared.web.RequestIdFilter;
import io.github.returnaction.fitness.identity.shared.web.RequestIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PendingLoginController {
    private final PendingLoginService login;

    @PostMapping(value = "/api/v1/auth/login", produces = "application/json")
    ResponseEntity<?> login(@RequestBody RegisterUserRequest request, HttpServletRequest servletRequest) {
        var result = login.login(request);
        var outcome = result.outcome();
        if (outcome == PendingLoginService.Outcome.ACTIVE) {
            var tokens = result.tokens();
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(new LoginTokenResponse(tokens.accessToken(), tokens.refreshToken(), "Bearer", 900, 2592000));
        }
        String code = switch (outcome) {
            case INVALID -> "invalid_credentials";
            case ACCOUNT_UNAVAILABLE -> "account_unavailable";
            case PENDING -> "registration_pending";
            case ACTIVE_LOGIN_UNAVAILABLE -> "identity_unavailable";
            case ACTIVE -> throw new IllegalStateException("Unexpected active login outcome");
        };
        String message = switch (outcome) {
            case INVALID -> "Invalid email or password.";
            case ACCOUNT_UNAVAILABLE -> "Account is unavailable.";
            case PENDING -> "Registration is being prepared. Try again later.";
            case ACTIVE_LOGIN_UNAVAILABLE -> "Login is temporarily unavailable.";
            case ACTIVE -> throw new IllegalStateException("Unexpected active login outcome");
        };
        Object attribute = servletRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = attribute instanceof String value
                ? value
                : RequestIdResolver.resolve(null).toString();
        var body = new ApiErrorResponse(new ApiErrorResponse.Error(code, message, List.of(), requestId));
        HttpStatus status = switch (outcome) {
            case INVALID -> HttpStatus.UNAUTHORIZED;
            case ACCOUNT_UNAVAILABLE -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
